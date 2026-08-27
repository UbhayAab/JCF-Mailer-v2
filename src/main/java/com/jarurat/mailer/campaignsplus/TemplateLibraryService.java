package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.CampaignRepository;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.repositories.SubscriberRepository;
import com.jarurat.mailer.services.SesSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The template side of composing: what merge fields a body uses, what it looks
 * like once those fields are resolved against a real subscriber, and what is
 * wrong with it before anyone hits send.
 *
 * Rendering goes through SesSender so a preview is the same HTML the recipient
 * gets, tracking rewrites and unsubscribe footer included.
 */
@Service
public class TemplateLibraryService {

    /**
     * Gmail clips a message past roughly 102 KB and hides everything after the
     * cut, which in practice means the unsubscribe footer disappears.
     */
    public static final int GMAIL_CLIP_BYTES = 102 * 1024;

    /** What a real send can fill. Owned by CampaignService so the two cannot drift. */
    public static final Set<String> CAMPAIGN_FIELDS = com.jarurat.mailer.services.CampaignService.SENDABLE_FIELDS.keySet();

    /** Resolved by the renderer itself, not by the merge map. */
    private static final Set<String> RESERVED = com.jarurat.mailer.merge.MergeTags.RESERVED;

    private static final Pattern MERGE_TAG = com.jarurat.mailer.merge.MergeTags.PATTERN;
    private static final Pattern INSECURE_LINK = Pattern.compile("href\\s*=\\s*\"http://", Pattern.CASE_INSENSITIVE);

    private static final String PREVIEW_TOKEN = "preview-token";

    public record Issue(String code, String severity, String message) {}

    public record Validation(boolean ok, int renderedBytes, int limitBytes, boolean overGmailClip,
                             List<String> mergeFields, List<String> unresolvedFields,
                             boolean hasUnsubscribeLink, List<Issue> issues) {}

    public record Sample(String describedAs, Map<String, String> fields) {}

    public record Preview(String subject, String html, String plainText,
                          Sample sample, Validation validation) {}

    private final EmailTemplateRepository templates;
    private final SubscriberRepository subscribers;
    private final CampaignRepository campaigns;
    private final SesSender ses;

    public TemplateLibraryService(EmailTemplateRepository templates,
                                  SubscriberRepository subscribers,
                                  CampaignRepository campaigns,
                                  SesSender ses) {
        this.templates = templates;
        this.subscribers = subscribers;
        this.campaigns = campaigns;
        this.ses = ses;
    }

    // ------------------------------------------------------------------
    // Merge fields
    // ------------------------------------------------------------------

    /** Every placeholder the copy uses, in the order it first appears. */
    public List<String> mergeFieldsOf(String subject, String html) {
        return mergeFieldsOf(subject, null, html);
    }

    /**
     * The preheader overload. It is a separate method rather than a third argument
     * on the old one because the old signature has callers, and a preheader tag was
     * silently invisible to validation before: the grey preview line is the second
     * thing a recipient reads and a raw {{CITY}} sitting in it is exactly the kind
     * of mistake this check exists to catch.
     */
    public List<String> mergeFieldsOf(String subject, String preheader, String html) {
        return com.jarurat.mailer.merge.MergeTags.extract(subject, preheader, html);
    }

    /**
     * A real subscriber only when the caller named one, because a preview against
     * invented data hides the case where every name in the file is blank.
     *
     * There is deliberately no "first row in the table" fallback. The callers are
     * gated on SUBSCRIBERS_READ only when subscriberId is present, so a fallback
     * would hand a real name and address to every caller who omitted the id.
     */
    public Sample sample(Long subscriberId) {
        Subscriber s = subscriberId == null ? null : subscribers.findById(subscriberId).orElse(null);

        Map<String, String> fields = new LinkedHashMap<>();
        if (s == null) {
            fields.put("NAME", "Dr. Sharma");
            fields.put("FIRST_NAME", "Dr. Sharma");
            fields.put("EMAIL", "sample@example.com");
            return new Sample("a made up subscriber, because no real one was named", fields);
        }

        String full = joined(s.getFirstName(), s.getLastName());
        fields.put("NAME", full);
        fields.put("FIRST_NAME", firstWord(full));
        fields.put("EMAIL", s.getEmail());
        return new Sample(s.getEmail(), fields);
    }

    // ------------------------------------------------------------------
    // Preview
    // ------------------------------------------------------------------

    public Preview previewTemplate(Long templateId, Long subscriberId) {
        EmailTemplate t = templates.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("No such template."));
        return previewRaw(t.getSubject(), t.getHtmlBody(), t.getType(), null, subscriberId, true, true);
    }

    public Preview previewCampaign(Long campaignId, Long subscriberId) {
        Campaign c = campaigns.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign."));
        return previewRaw(c.getSubject(), c.getHtmlBody(), "MARKETING", c.getPreheader(),
                subscriberId, c.isTrackOpens(), c.isTrackClicks());
    }

    public Preview previewRaw(String subject, String html, String type, String preheader,
                              Long subscriberId, boolean trackOpens, boolean trackClicks) {
        Sample sample = sample(subscriberId);
        String renderedSubject = ses.renderSubject(subject, sample.fields());
        String renderedHtml = render(html, type, preheader, sample, trackOpens, trackClicks);
        Validation validation = validate(subject, html, type, preheader, sample, trackOpens, trackClicks);
        return new Preview(renderedSubject, renderedHtml, ses.toPlainText(renderedHtml), sample, validation);
    }

    private String render(String html, String type, String preheader, Sample sample,
                          boolean trackOpens, boolean trackClicks) {
        if (isTransactional(type)) return ses.renderTransactional(nz(html), sample.fields());
        return ses.renderMarketing(nz(html), PREVIEW_TOKEN, sample.fields(),
                preheader, trackOpens, trackClicks);
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    public Validation validate(String subject, String html, String type, String preheader,
                               Sample sample, boolean trackOpens, boolean trackClicks) {
        List<Issue> issues = new ArrayList<>();
        String rendered = render(html, type, preheader, sample, trackOpens, trackClicks);
        int bytes = rendered.getBytes(StandardCharsets.UTF_8).length;

        if (isBlank(subject)) issues.add(new Issue("SUBJECT_MISSING", "ERROR", "The subject line is empty."));
        if (isBlank(html)) issues.add(new Issue("BODY_MISSING", "ERROR", "The email body is empty."));

        List<String> fields = mergeFieldsOf(subject, html);
        List<String> unresolved = new ArrayList<>();
        for (String f : fields) if (!sample.fields().containsKey(f)) unresolved.add(f);

        if (!unresolved.isEmpty()) {
            boolean transactional = isTransactional(type);
            issues.add(new Issue("UNRESOLVED_FIELDS", transactional ? "INFO" : "WARN",
                    transactional
                            ? "The caller must supply " + String.join(", ", unresolved) + " on every send."
                            : "A campaign send only fills " + String.join(", ", sorted(CAMPAIGN_FIELDS))
                              + ". " + String.join(", ", unresolved) + " will render empty."));
        }

        boolean hasUnsubscribe = rendered.contains("/api/mailer/unsubscribe");
        if (!isTransactional(type)) {
            if (!hasUnsubscribe) {
                issues.add(new Issue("UNSUBSCRIBE_MISSING", "ERROR",
                        "The rendered email has no unsubscribe link. Do not send this."));
            } else if (!nz(html).contains("{{UNSUBSCRIBE_LINK}}")) {
                issues.add(new Issue("UNSUBSCRIBE_AUTOMATIC", "INFO",
                        "No {{UNSUBSCRIBE_LINK}} in the body, so the standard footer was added at the end."));
            }
        }

        if (bytes > GMAIL_CLIP_BYTES) {
            issues.add(new Issue("OVER_GMAIL_CLIP", "WARN",
                    "Rendered size is " + kb(bytes) + " KB. Gmail clips past " + kb(GMAIL_CLIP_BYTES)
                    + " KB and hides everything after the cut, unsubscribe footer included."));
        }

        int open = count(nz(subject) + nz(html), "{{");
        int close = count(nz(subject) + nz(html), "}}");
        if (open != close) {
            issues.add(new Issue("UNBALANCED_MERGE_TAGS", "WARN",
                    "There are " + open + " opening {{ and " + close + " closing }}. "
                    + "A mistyped tag ships to the recipient as literal text."));
        }

        if (INSECURE_LINK.matcher(nz(html)).find()) {
            issues.add(new Issue("INSECURE_LINKS", "WARN",
                    "At least one link is http:// rather than https://, which filters treat as a spam signal."));
        }

        // Stripped here rather than through SesSender.toPlainText, which is
        // measured on the rendered body and substitutes a fallback sentence when
        // there is no text at all. Both would hide an image only email.
        String visible = nz(html)
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
        if (visible.length() < 40 && nz(html).toLowerCase(Locale.ROOT).contains("<img")) {
            issues.add(new Issue("IMAGE_ONLY", "WARN",
                    "Almost no text outside the images. Image only mail lands in spam and is unreadable "
                    + "for anyone who blocks images."));
        }

        if (!isBlank(html) && !nz(html).toLowerCase(Locale.ROOT).contains("<body")) {
            issues.add(new Issue("NO_HTML_DOCUMENT", "INFO",
                    "The body is an HTML fragment rather than a full document. Outlook renders fragments "
                    + "less predictably."));
        }

        boolean ok = issues.stream().noneMatch(i -> "ERROR".equals(i.severity()));
        return new Validation(ok, bytes, GMAIL_CLIP_BYTES, bytes > GMAIL_CLIP_BYTES,
                fields, unresolved, hasUnsubscribe, issues);
    }

    public Validation validateTemplate(Long templateId) {
        EmailTemplate t = templates.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("No such template."));
        return validate(t.getSubject(), t.getHtmlBody(), t.getType(), null, sample(null), true, true);
    }

    // ------------------------------------------------------------------
    // Library
    // ------------------------------------------------------------------

    /** The whole library with its merge fields and its verdict, one call. */
    public List<Map<String, Object>> library(String type) {
        List<EmailTemplate> found = (type == null || type.isBlank())
                ? templates.findAllByOrderByCreatedAtDesc()
                : templates.findByTypeOrderByCreatedAtDesc(type);

        Sample sample = sample(null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailTemplate t : found) {
            Validation v = validate(t.getSubject(), t.getHtmlBody(), t.getType(), null, sample, true, true);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            m.put("subject", nz(t.getSubject()));
            m.put("type", t.getType());
            m.put("description", nz(t.getDescription()));
            m.put("mergeFields", v.mergeFields());
            m.put("unresolvedFields", v.unresolvedFields());
            m.put("renderedBytes", v.renderedBytes());
            m.put("overGmailClip", v.overGmailClip());
            m.put("hasUnsubscribeLink", v.hasUnsubscribeLink());
            m.put("ok", v.ok());
            m.put("issues", v.issues());
            m.put("createdBy", nz(t.getCreatedBy()));
            m.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** Loads a template into a draft campaign. The point of having a library. */
    @Transactional
    public Campaign applyToCampaign(Long templateId, Long campaignId, boolean overwriteSubject) {
        EmailTemplate t = templates.findById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("No such template."));
        Campaign c = campaigns.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign."));
        if (!c.isEditable())
            throw new IllegalStateException("A campaign that is sending or already sent cannot be edited.");

        c.setHtmlBody(t.getHtmlBody());
        if (overwriteSubject || isBlank(c.getSubject())) c.setSubject(t.getSubject());
        return campaigns.save(c);
    }

    /** Saves the current campaign copy back into the library so it can be reused. */
    @Transactional
    public EmailTemplate saveCampaignAsTemplate(Long campaignId, String name, String description) {
        Campaign c = campaigns.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign."));
        if (isBlank(name)) throw new IllegalArgumentException("Give the template a name.");
        if (isBlank(c.getHtmlBody())) throw new IllegalArgumentException("That campaign has no body to save.");

        String slug = EmailTemplate.slugify(name);
        if (templates.existsBySlug(slug))
            throw new IllegalArgumentException("A template with the slug \"" + slug + "\" already exists.");

        EmailTemplate t = new EmailTemplate(name.trim(), slug, c.getSubject(), c.getHtmlBody(),
                "MARKETING", c.getCreatedBy());
        t.setDescription(isBlank(description) ? "Saved from campaign " + c.getName() : description);
        t.setUpdatedAt(LocalDateTime.now());
        return templates.save(t);
    }

    // ------------------------------------------------------------------

    private static boolean isTransactional(String type) { return "TRANSACTIONAL".equalsIgnoreCase(nz(type)); }

    private static String joined(String first, String last) {
        String full = (nz(first) + " " + nz(last)).trim();
        return full.isEmpty() ? "there" : full;
    }

    private static String firstWord(String s) {
        String trimmed = nz(s).trim();
        if (trimmed.isEmpty()) return "there";
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static List<String> sorted(Set<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(String::compareTo);
        return out;
    }

    private static int count(String haystack, String needle) {
        int n = 0, at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }

    private static String kb(int bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1024.0);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
