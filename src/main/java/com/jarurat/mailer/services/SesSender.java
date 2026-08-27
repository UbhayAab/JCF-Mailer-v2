package com.jarurat.mailer.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that touches Amazon SES. Owns the send-rate budget for the whole
 * process so campaigns and transactional mail cannot collectively exceed the
 * account limit and start eating throttles.
 */
@Service
public class SesSender {

    public static final Pattern EMAIL_OK =
            Pattern.compile("^[^@\\s,;]+@[^@\\s,;.]+\\.[^@\\s,;]+$");

    private static final Pattern TRACK_TAG = Pattern.compile("\\{\\{TRACK:(.*?)\\}\\}");
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_CLOSE = Pattern.compile("</body>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_OPEN = Pattern.compile("<body[^>]*>", Pattern.CASE_INSENSITIVE);
    /** The one definition, shared with the composer so both agree on what a tag is. */
    private static final Pattern MERGE_TAG = com.jarurat.mailer.merge.MergeTags.PATTERN;

    private final SesV2Client ses;
    private final String fromEmail;
    private final String defaultFromName;
    private final String defaultReplyTo;
    private final String appDomain;
    private final int maxSendRate;
    private final String configurationSet;

    private final AtomicLong nextSlotNanos = new AtomicLong(System.nanoTime());

    public SesSender(@Value("${aws.region}") String region,
                     @Value("${aws.ses.fromEmail}") String fromEmail,
                     @Value("${aws.ses.fromName}") String defaultFromName,
                     @Value("${aws.ses.replyTo}") String defaultReplyTo,
                     @Value("${aws.ses.maxSendRate:12}") int maxSendRate,
                     @Value("${aws.ses.configurationSet:}") String configurationSet,
                     @Value("${app.domain}") String appDomain) {
        this.ses = SesV2Client.builder().region(Region.of(region)).build();
        this.fromEmail = fromEmail;
        this.defaultFromName = defaultFromName;
        this.defaultReplyTo = defaultReplyTo;
        this.maxSendRate = Math.max(1, maxSendRate);
        this.configurationSet = configurationSet;
        this.appDomain = appDomain.endsWith("/") ? appDomain.substring(0, appDomain.length() - 1) : appDomain;
    }

    public String getAppDomain() { return appDomain; }
    public SesV2Client client() { return ses; }

    // ------------------------------------------------------------------
    // Rate limiting
    // ------------------------------------------------------------------

    /** Hands out evenly spaced send slots. Parking a virtual thread is nearly free. */
    public void awaitSendSlot() {
        long interval = 1_000_000_000L / maxSendRate;
        while (true) {
            long now = System.nanoTime();
            long previous = nextSlotNanos.get();
            long slot = Math.max(now, previous);
            if (nextSlotNanos.compareAndSet(previous, slot + interval)) {
                long wait = slot - now;
                if (wait > 0) LockSupport.parkNanos(wait);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    public record Outgoing(String to, String subject, String html, String fromName,
                           String replyTo, String unsubscribeUrl) {}

    public String send(Outgoing out) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            awaitSendSlot();
            try {
                return ses.sendEmail(build(out)).messageId();
            } catch (TooManyRequestsException | SendingPausedException e) {
                last = e;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250L * attempt * attempt));
            } catch (AccountSuspendedException | MailFromDomainNotVerifiedException
                     | MessageRejectedException e) {
                throw e; // retrying will not help
            } catch (RuntimeException e) {
                last = e;
                if (attempt == 4) throw e;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200L * attempt));
            }
        }
        throw last;
    }

    private SendEmailRequest build(Outgoing out) {
        Message.Builder message = Message.builder()
                .subject(Content.builder().data(out.subject()).charset("UTF-8").build())
                .body(Body.builder()
                        .html(Content.builder().data(out.html()).charset("UTF-8").build())
                        .text(Content.builder().data(toPlainText(out.html())).charset("UTF-8").build())
                        .build());

        // Gmail and Yahoo require one-click unsubscribe from bulk senders. Transactional
        // mail deliberately has no unsubscribe, so the URL may be absent.
        if (out.unsubscribeUrl() != null) {
            message.headers(
                    MessageHeader.builder().name("List-Unsubscribe")
                            .value("<" + out.unsubscribeUrl() + ">").build(),
                    MessageHeader.builder().name("List-Unsubscribe-Post")
                            .value("List-Unsubscribe=One-Click").build());
        }

        String name = blankToNull(out.fromName()) != null ? out.fromName() : defaultFromName;
        String reply = blankToNull(out.replyTo()) != null ? out.replyTo() : defaultReplyTo;

        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(name == null ? fromEmail
                        : "\"" + name.replace("\"", "") + "\" <" + fromEmail + ">")
                .destination(Destination.builder().toAddresses(out.to()).build())
                .content(EmailContent.builder().simple(message.build()).build());

        if (reply != null && !reply.isBlank()) request.replyToAddresses(reply);

        // Without a configuration set, SES v2 publishes no per-message events, so
        // every campaign row in the message log stops at "SES accepted" and never
        // learns what the recipient's server said. Identity level notifications
        // are the v1 mechanism and do not cover these sends. Left blank the send
        // still works, it just goes unobserved.
        if (configurationSet != null && !configurationSet.isBlank()) {
            request.configurationSetName(configurationSet);
        }
        return request.build();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Applies merge fields, rewrites links for click tracking, injects the open
     * pixel, and guarantees an unsubscribe link exists on marketing mail.
     */
    public String renderMarketing(String rawHtml, String token, Map<String, String> mergeFields,
                                  String preheader, boolean trackOpens, boolean trackClicks) {
        String unsubscribeUrl = appDomain + "/api/mailer/unsubscribe?token=" + token;
        String html = applyMergeFields(rawHtml, mergeFields, true);

        if (trackClicks) {
            StringBuilder sb = new StringBuilder();
            Matcher m = TRACK_TAG.matcher(html);
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(trackedUrl(token, m.group(1))));
            m.appendTail(sb);
            html = sb.toString();

            StringBuilder linked = new StringBuilder();
            Matcher hm = HREF.matcher(html);
            while (hm.find()) {
                String url = hm.group(1);
                String replacement = url.startsWith(appDomain) || url.contains("/api/mailer/")
                        ? hm.group(0)
                        : "href=\"" + trackedUrl(token, url) + "\"";
                hm.appendReplacement(linked, Matcher.quoteReplacement(replacement));
            }
            hm.appendTail(linked);
            html = linked.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            Matcher m = TRACK_TAG.matcher(html);
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1)));
            m.appendTail(sb);
            html = sb.toString();
        }

        if (html.contains("{{UNSUBSCRIBE_LINK}}")) {
            html = html.replace("{{UNSUBSCRIBE_LINK}}", unsubscribeUrl);
        } else {
            html = appendBeforeBodyClose(html,
                    "<div style=\"margin-top:28px;padding-top:16px;border-top:1px solid #e5e7eb;"
                    + "font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#9ca3af;text-align:center\">"
                    + "You are receiving this because you registered with Jarurat Care Foundation.<br>"
                    + "<a href=\"" + unsubscribeUrl + "\" style=\"color:#9ca3af\">Unsubscribe</a></div>");
        }

        html = injectPreheader(html, preheader);

        if (trackOpens) {
            html = appendBeforeBodyClose(html, "<img src=\"" + appDomain + "/api/mailer/open?token=" + token
                    + "\" width=\"1\" height=\"1\" alt=\"\" style=\"display:block;border:0\">");
        }
        return html;
    }

    /** Transactional mail: merge fields only. No tracking, no unsubscribe footer. */
    public String renderTransactional(String rawHtml, Map<String, String> mergeFields) {
        return applyMergeFields(rawHtml, mergeFields, true);
    }

    /**
     * Merges a subject line, deliberately without HTML escaping.
     *
     * A Subject header is not HTML. Running it through the body escaper is how
     * "Ram & Co" reached an inbox as "Ram &amp; Co" and "Priya O'Brien" as
     * "Priya O&#39;Brien" - visible in the message list, in every reply, and in the
     * message log. The escaping that is correct in the body is a bug here.
     *
     * What it does instead is strip CR, LF and NUL. Those cannot appear in a header
     * without splitting it. SES v2's simple content builds the MIME itself so a
     * newline could not currently smuggle in a Bcc, but that safety is a property of
     * the API being used, not of the input, and it is one switch to sendRawEmail
     * away from being header injection.
     */
    public String renderSubject(String rawSubject, Map<String, String> mergeFields) {
        String merged = applyMergeFields(rawSubject == null ? "" : rawSubject, mergeFields, false);
        String cleaned = merged.replaceAll("[\\r\\n\\u0000]", " ").replaceAll("\\s{2,}", " ").trim();
        return cleaned.length() <= MAX_SUBJECT_CHARS ? cleaned : cleaned.substring(0, MAX_SUBJECT_CHARS);
    }

    /**
     * Long enough never to truncate a real subject, short enough that a merge value
     * carrying a runaway string cannot push the header past what a receiver accepts.
     */
    private static final int MAX_SUBJECT_CHARS = 500;

    /**
     * Unreplaced tags are blanked rather than shipped to a candidate as "{{ROLE}}".
     *
     * escapeValues is true for anything that lands in HTML and false for a subject.
     * It is a parameter rather than two copies of the loop because the substitution
     * rules, including the two reserved tags, must not be allowed to drift apart.
     */
    private String applyMergeFields(String html, Map<String, String> fields, boolean escapeValues) {
        Map<String, String> normalised = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((k, v) -> normalised.put(k.toUpperCase(),
                    v == null ? "" : (escapeValues ? escape(v) : v)));
        }
        StringBuilder out = new StringBuilder();
        Matcher m = MERGE_TAG.matcher(html);
        while (m.find()) {
            String key = m.group(1).toUpperCase();
            if (key.equals("UNSUBSCRIBE_LINK") || key.equals("TRACK")) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(normalised.getOrDefault(key, "")));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private String injectPreheader(String html, String preheader) {
        if (preheader == null || preheader.isBlank()) return html;
        String hidden = "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0\">"
                + escape(preheader) + "</div>";
        Matcher bo = BODY_OPEN.matcher(html);
        return bo.find() ? html.substring(0, bo.end()) + hidden + html.substring(bo.end()) : hidden + html;
    }

    private String appendBeforeBodyClose(String html, String fragment) {
        Matcher bc = BODY_CLOSE.matcher(html);
        return bc.find() ? html.substring(0, bc.start()) + fragment + html.substring(bc.start()) : html + fragment;
    }

    private String trackedUrl(String token, String rawUrl) {
        return appDomain + "/api/mailer/click?token=" + token + "&url="
                + java.net.URLEncoder.encode(rawUrl, StandardCharsets.UTF_8);
    }

    public String toPlainText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|h[1-6]|li)>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return text.isEmpty() ? "Please view this message in an HTML capable email client." : text;
    }

    /**
     * Merge values are substituted before links are rewritten, so a value can land
     * inside href="..." . Without the quote escape a value of
     *   " onmouseover="alert(1)
     * closes the attribute and adds its own, which is a script injection into every
     * recipient's mail from whatever fed the subscriber row. Escaping the quote is
     * harmless in text position, so it is unconditional rather than context aware.
     */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    // ------------------------------------------------------------------
    // Account health
    // ------------------------------------------------------------------

    public Map<String, Object> accountHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            GetAccountResponse acct = ses.getAccount(GetAccountRequest.builder().build());
            out.put("productionAccess", acct.productionAccessEnabled());
            out.put("sendingEnabled", acct.sendingEnabled());
            out.put("enforcementStatus", acct.enforcementStatus());
            out.put("max24Hour", acct.sendQuota().max24HourSend());
            out.put("sentLast24Hours", acct.sendQuota().sentLast24Hours());
            out.put("maxSendRate", acct.sendQuota().maxSendRate());
            out.put("configuredRate", maxSendRate);
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** Reports whether the custom MAIL FROM domain has gone live in DNS yet. */
    public Map<String, Object> identityHealth(String domain) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            GetEmailIdentityResponse id = ses.getEmailIdentity(
                    GetEmailIdentityRequest.builder().emailIdentity(domain).build());
            out.put("domain", domain);
            out.put("verified", id.verifiedForSendingStatus());
            out.put("dkimStatus", id.dkimAttributes() == null ? "UNKNOWN"
                    : String.valueOf(id.dkimAttributes().statusAsString()));
            out.put("mailFromDomain", id.mailFromAttributes() == null ? null
                    : id.mailFromAttributes().mailFromDomain());
            out.put("mailFromStatus", id.mailFromAttributes() == null ? null
                    : String.valueOf(id.mailFromAttributes().mailFromDomainStatusAsString()));
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
