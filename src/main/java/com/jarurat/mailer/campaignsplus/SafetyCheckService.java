package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.repositories.CampaignRecipientRepository;
import com.jarurat.mailer.repositories.CampaignRepository;
import com.jarurat.mailer.repositories.ListMemberRepository;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.services.SesSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The last gate before a blast leaves the building. Everything here is a
 * question the sender would be asked afterwards if it went wrong: was there an
 * unsubscribe link, did anyone on the suppression list get mailed, was the
 * sending domain actually verified, was the account already bouncing.
 *
 * Blockers stop the send. Warnings are printed and the send continues, because
 * a check that cries wolf gets switched off.
 */
@Service
public class SafetyCheckService {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";

    public record Finding(String code, String severity, String message, String detail) {}

    public record Report(Long campaignId, String campaignName, boolean passed,
                         List<Finding> blockers, List<Finding> warnings, List<Finding> notes,
                         Map<String, Object> facts, String checkedAt) {}

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final ListMemberRepository members;
    private final MailingListRepository lists;
    private final CampaignsPlusRepository queries;
    private final TemplateLibraryService templateLibrary;
    private final SesSender ses;
    private final ObjectProvider<AudienceRiskSource> riskSource;

    private final String sendingDomain;
    private final int bounceWindowDays;
    private final int bounceMinSample;
    private final double bounceBlockPct;
    private final double bounceWarnPct;
    private final double complaintBlockPct;
    private final double complaintWarnPct;

    public SafetyCheckService(CampaignRepository campaigns,
                              CampaignRecipientRepository recipients,
                              ListMemberRepository members,
                              MailingListRepository lists,
                              CampaignsPlusRepository queries,
                              TemplateLibraryService templateLibrary,
                              SesSender ses,
                              ObjectProvider<AudienceRiskSource> riskSource,
                              @Value("${aws.ses.domain:jarurat.care}") String sendingDomain,
                              @Value("${mailer.safety.bounceWindowDays:30}") int bounceWindowDays,
                              @Value("${mailer.safety.bounceMinSample:100}") int bounceMinSample,
                              @Value("${mailer.safety.bounceBlockPct:5.0}") double bounceBlockPct,
                              @Value("${mailer.safety.bounceWarnPct:2.0}") double bounceWarnPct,
                              @Value("${mailer.safety.complaintBlockPct:0.5}") double complaintBlockPct,
                              @Value("${mailer.safety.complaintWarnPct:0.1}") double complaintWarnPct) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.members = members;
        this.lists = lists;
        this.queries = queries;
        this.templateLibrary = templateLibrary;
        this.ses = ses;
        this.riskSource = riskSource;
        this.sendingDomain = sendingDomain;
        this.bounceWindowDays = bounceWindowDays;
        this.bounceMinSample = bounceMinSample;
        this.bounceBlockPct = bounceBlockPct;
        this.bounceWarnPct = bounceWarnPct;
        this.complaintBlockPct = complaintBlockPct;
        this.complaintWarnPct = complaintWarnPct;
    }

    public Report check(Long campaignId) {
        return check(campaigns.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign.")));
    }

    /**
     * Throws with the first blocking reason. This is the call the send path
     * makes, so a blocked campaign fails loudly instead of half sending.
     */
    public void assertSafeToSend(Campaign campaign) {
        Report report = check(campaign);
        if (report.passed()) return;
        Finding first = report.blockers().get(0);
        String extra = report.blockers().size() == 1 ? ""
                : " (" + report.blockers().size() + " blocking problems in total)";
        throw new IllegalStateException("Pre-send safety check failed: " + first.message() + extra);
    }

    /**
     * Clears a SUPPRESSED_QUEUED block by marking those rows SKIPPED, which is
     * what the send loop would have done had it reached them. Nothing else can
     * clear it: re-queueing skips rows that already exist.
     */
    public int dropSuppressedQueued(Long campaignId) {
        campaigns.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("No such campaign."));
        return queries.skipSuppressedPending(campaignId);
    }

    public Report check(Campaign campaign) {
        List<Finding> blockers = new ArrayList<>();
        List<Finding> warnings = new ArrayList<>();
        List<Finding> notes = new ArrayList<>();
        Map<String, Object> facts = new LinkedHashMap<>();

        facts.put("campaignStatus", campaign.getStatus());
        facts.put("sendingDomain", sendingDomain);

        checkCopy(campaign, blockers, warnings, notes, facts);
        long audience = checkAudience(campaign, blockers, warnings, notes, facts);
        checkSuppression(campaign, blockers, facts);
        checkSesAccount(audience, blockers, warnings, facts);
        checkSenderDomain(blockers, warnings, facts);
        checkBounceHistory(blockers, warnings, notes, facts);
        checkVerificationEstimate(campaign, blockers, warnings, notes, facts);

        return new Report(campaign.getId(), campaign.getName(), blockers.isEmpty(),
                blockers, warnings, notes, facts, LocalDateTime.now().toString());
    }

    // ------------------------------------------------------------------
    // The copy itself
    // ------------------------------------------------------------------

    private void checkCopy(Campaign campaign, List<Finding> blockers, List<Finding> warnings,
                           List<Finding> notes, Map<String, Object> facts) {
        TemplateLibraryService.Validation v = templateLibrary.validate(
                campaign.getSubject(), campaign.getHtmlBody(), "MARKETING", campaign.getPreheader(),
                templateLibrary.sample(null), campaign.isTrackOpens(), campaign.isTrackClicks());

        facts.put("renderedBytes", v.renderedBytes());
        facts.put("gmailClipBytes", v.limitBytes());
        facts.put("hasUnsubscribeLink", v.hasUnsubscribeLink());
        facts.put("mergeFields", v.mergeFields());

        for (TemplateLibraryService.Issue issue : v.issues()) {
            Finding f = new Finding(issue.code(), severityOf(issue.severity()), issue.message(), null);
            switch (f.severity()) {
                case BLOCK -> blockers.add(f);
                case WARN -> warnings.add(f);
                default -> notes.add(f);
            }
        }

        if (campaign.getReplyTo() != null && !campaign.getReplyTo().isBlank()
                && !SesSender.EMAIL_OK.matcher(campaign.getReplyTo().trim()).matches()) {
            warnings.add(new Finding("REPLY_TO_INVALID", WARN,
                    "The reply-to address does not look like an email address.", campaign.getReplyTo()));
        }
        if (!campaign.isTrackOpens() && !campaign.isTrackClicks()) {
            notes.add(new Finding("TRACKING_OFF", INFO,
                    "Open and click tracking are both off, so this campaign will report no engagement.", null));
        }
    }

    // ------------------------------------------------------------------
    // Who it goes to
    // ------------------------------------------------------------------

    private long checkAudience(Campaign campaign, List<Finding> blockers, List<Finding> warnings,
                               List<Finding> notes, Map<String, Object> facts) {
        Long listId = campaign.getListId();
        if (listId == null) {
            blockers.add(new Finding("LIST_MISSING", BLOCK, "No audience list is attached.", null));
            return 0;
        }
        if (!lists.existsById(listId)) {
            blockers.add(new Finding("LIST_DELETED", BLOCK,
                    "The audience list this campaign points at no longer exists.", "listId " + listId));
            return 0;
        }

        long listSize = members.countByListId(listId);
        long mailable = members.countMailable(listId);
        long unmailable = queries.countListUnmailable(listId);
        long neverMailed = queries.countListNeverMailed(listId);
        long pending = recipients.countByCampaignIdAndStatus(campaign.getId(), "PENDING");
        long alreadySent = recipients.countByCampaignIdAndStatus(campaign.getId(), "SENT");

        facts.put("listSize", listSize);
        facts.put("mailable", mailable);
        facts.put("unmailableOnList", unmailable);
        facts.put("neverMailed", neverMailed);
        facts.put("queuedPending", pending);
        facts.put("alreadySent", alreadySent);

        long audience = pending > 0 ? pending : mailable;
        facts.put("audience", audience);

        if (audience == 0) {
            blockers.add(new Finding("AUDIENCE_EMPTY", BLOCK,
                    listSize == 0 ? "The audience list is empty."
                            : "Every one of the " + listSize + " people on this list is unsubscribed, "
                              + "bounced or suppressed.", null));
            return 0;
        }
        if (unmailable > 0) {
            notes.add(new Finding("AUDIENCE_FILTERED", INFO,
                    unmailable + " of the " + listSize + " people on this list will be skipped: "
                    + "unsubscribed, bounced or suppressed.", null));
        }
        if (alreadySent > 0) {
            notes.add(new Finding("PARTIAL_RESEND", INFO,
                    alreadySent + " recipient(s) on this campaign were already sent to and will not "
                    + "be mailed again.", null));
        }
        if (listSize > 0 && neverMailed * 2 > listSize) {
            warnings.add(new Finding("COLD_AUDIENCE", WARN,
                    neverMailed + " of " + listSize + " addresses have never been mailed, so there is no "
                    + "bounce history for most of this list. Send a smaller batch first.", null));
        }
        return audience;
    }

    /**
     * The one leak the send loop cannot close by itself: rows queued before a
     * suppression arrived, on a run that died before reaching them.
     */
    private void checkSuppression(Campaign campaign, List<Finding> blockers, Map<String, Object> facts) {
        long suppressedPending = queries.countSuppressedPending(campaign.getId());
        facts.put("suppressedStillQueued", suppressedPending);
        if (suppressedPending == 0) return;

        List<String> sample = queries.sampleSuppressedPending(campaign.getId(), 5);
        blockers.add(new Finding("SUPPRESSED_QUEUED", BLOCK,
                suppressedPending + " queued recipient(s) are on the suppression list. Re-queue the "
                + "audience before sending.", String.join(", ", sample)));
    }

    // ------------------------------------------------------------------
    // The account we send through
    // ------------------------------------------------------------------

    private void checkSesAccount(long audience, List<Finding> blockers, List<Finding> warnings,
                                 Map<String, Object> facts) {
        Map<String, Object> account = ses.accountHealth();
        if (!Boolean.TRUE.equals(account.get("ok"))) {
            warnings.add(new Finding("SES_UNREACHABLE", WARN,
                    "Could not read the SES account status, so quota and sending state are unverified.",
                    String.valueOf(account.get("error"))));
            return;
        }

        facts.put("sesProductionAccess", account.get("productionAccess"));
        facts.put("sesSendingEnabled", account.get("sendingEnabled"));
        facts.put("sesEnforcementStatus", account.get("enforcementStatus"));

        if (Boolean.FALSE.equals(account.get("sendingEnabled"))) {
            blockers.add(new Finding("SES_SENDING_DISABLED", BLOCK,
                    "SES has sending disabled on this account.", null));
        }
        String enforcement = account.get("enforcementStatus") == null ? ""
                : String.valueOf(account.get("enforcementStatus"));
        if (enforcement.equalsIgnoreCase("SHUTDOWN")) {
            blockers.add(new Finding("SES_SHUTDOWN", BLOCK,
                    "The SES account is under a sending shutdown.", enforcement));
        } else if (!enforcement.isEmpty() && !enforcement.equalsIgnoreCase("HEALTHY")) {
            warnings.add(new Finding("SES_ENFORCEMENT", WARN,
                    "SES reports the account as " + enforcement + " rather than HEALTHY.", null));
        }
        if (Boolean.FALSE.equals(account.get("productionAccess"))) {
            warnings.add(new Finding("SES_SANDBOX", WARN,
                    "The SES account is still in the sandbox, so mail only reaches verified addresses.", null));
        }

        Double max = toDouble(account.get("max24Hour"));
        Double used = toDouble(account.get("sentLast24Hours"));
        if (max == null || used == null || max <= 0) return; // -1 means no daily cap

        long remaining = (long) Math.max(0, max - used);
        facts.put("quota24h", max.longValue());
        facts.put("quotaRemaining", remaining);
        if (audience > remaining) {
            blockers.add(new Finding("QUOTA_EXCEEDED", BLOCK,
                    "This campaign needs " + audience + " sends and SES has " + remaining
                    + " left in the 24 hour quota.", null));
        } else if (remaining > 0 && audience > remaining * 0.8) {
            warnings.add(new Finding("QUOTA_TIGHT", WARN,
                    "This campaign uses " + Math.round(audience * 100.0 / remaining)
                    + "% of the remaining 24 hour quota. Transactional mail may be throttled behind it.",
                    null));
        }
    }

    private void checkSenderDomain(List<Finding> blockers, List<Finding> warnings, Map<String, Object> facts) {
        Map<String, Object> identity = ses.identityHealth(sendingDomain);
        if (!Boolean.TRUE.equals(identity.get("ok"))) {
            warnings.add(new Finding("DOMAIN_UNVERIFIABLE", WARN,
                    "Could not check whether " + sendingDomain + " is verified with SES.",
                    String.valueOf(identity.get("error"))));
            return;
        }

        facts.put("domainVerified", identity.get("verified"));
        facts.put("dkimStatus", identity.get("dkimStatus"));
        facts.put("mailFromStatus", identity.get("mailFromStatus"));

        if (!Boolean.TRUE.equals(identity.get("verified"))) {
            blockers.add(new Finding("DOMAIN_NOT_VERIFIED", BLOCK,
                    "The sending domain " + sendingDomain + " is not verified for sending in SES.", null));
        }
        String dkim = identity.get("dkimStatus") == null ? "" : String.valueOf(identity.get("dkimStatus"));
        if (!dkim.isEmpty() && !dkim.equalsIgnoreCase("SUCCESS")) {
            warnings.add(new Finding("DKIM_NOT_READY", WARN,
                    "DKIM for " + sendingDomain + " is " + dkim + ". Gmail and Yahoo require signed mail "
                    + "from bulk senders.", null));
        }
        Object mailFrom = identity.get("mailFromStatus");
        if (mailFrom != null && !String.valueOf(mailFrom).equalsIgnoreCase("SUCCESS")) {
            warnings.add(new Finding("MAIL_FROM_NOT_READY", WARN,
                    "The custom MAIL FROM domain is " + mailFrom + ", so SPF aligns to amazonses.com "
                    + "rather than " + sendingDomain + ".", null));
        }
    }

    // ------------------------------------------------------------------
    // Reputation
    // ------------------------------------------------------------------

    /**
     * There is no per-address verification data to lean on, so this is measured
     * from what the account actually did: hard bounces and complaints recorded
     * against sends in the recent window. Below the sample floor it reports
     * nothing rather than inventing a rate from four sends.
     */
    private void checkBounceHistory(List<Finding> blockers, List<Finding> warnings,
                                    List<Finding> notes, Map<String, Object> facts) {
        LocalDateTime since = LocalDateTime.now().minusDays(bounceWindowDays);
        long sent = queries.countSentSince(since);
        long bounced = queries.countBouncesSince(since);
        long complained = queries.countComplaintsSince(since);

        facts.put("windowDays", bounceWindowDays);
        facts.put("sentInWindow", sent);
        facts.put("bouncesInWindow", bounced);
        facts.put("complaintsInWindow", complained);

        if (sent < bounceMinSample) {
            facts.put("bounceRatePct", null);
            facts.put("complaintRatePct", null);
            notes.add(new Finding("BOUNCE_RATE_UNKNOWN", INFO,
                    "Only " + sent + " message(s) were sent in the last " + bounceWindowDays
                    + " days, which is too few to estimate a bounce rate.", null));
            return;
        }

        double bounceRate = round1(bounced * 100.0 / sent);
        double complaintRate = round2(complained * 100.0 / sent);
        facts.put("bounceRatePct", bounceRate);
        facts.put("complaintRatePct", complaintRate);

        if (bounceRate >= bounceBlockPct) {
            blockers.add(new Finding("BOUNCE_RATE_HIGH", BLOCK,
                    "The bounce rate over the last " + bounceWindowDays + " days is " + pct(bounceRate)
                    + ", at or above the " + pct(bounceBlockPct) + " level where SES puts an account "
                    + "under review. Clean the list before sending more.", bounced + " of " + sent));
        } else if (bounceRate >= bounceWarnPct) {
            warnings.add(new Finding("BOUNCE_RATE_RISING", WARN,
                    "The bounce rate over the last " + bounceWindowDays + " days is " + pct(bounceRate)
                    + ". SES starts reviewing accounts at " + pct(bounceBlockPct) + ".",
                    bounced + " of " + sent));
        }

        if (complaintRate >= complaintBlockPct) {
            blockers.add(new Finding("COMPLAINT_RATE_HIGH", BLOCK,
                    "The complaint rate is " + pct(complaintRate) + ", at or above the "
                    + pct(complaintBlockPct) + " level that gets sending suspended.",
                    complained + " of " + sent));
        } else if (complaintRate >= complaintWarnPct) {
            warnings.add(new Finding("COMPLAINT_RATE_RISING", WARN,
                    "The complaint rate is " + pct(complaintRate) + ". SES expects it under "
                    + pct(complaintWarnPct) + ".", complained + " of " + sent));
        }
    }

    /**
     * The forward looking half of the bounce question, which history cannot
     * answer for a list nobody has mailed yet. Silent when no verification data
     * has been wired in, because a fabricated estimate would block real sends.
     */
    private void checkVerificationEstimate(Campaign campaign, List<Finding> blockers,
                                           List<Finding> warnings, List<Finding> notes,
                                           Map<String, Object> facts) {
        AudienceRiskSource source = riskSource.getIfUnique();
        if (source == null || campaign.getListId() == null) {
            facts.put("verifiedBouncePct", null);
            return;
        }

        Double estimate;
        try {
            estimate = source.estimatedBouncePct(campaign.getListId()).orElse(null);
        } catch (RuntimeException e) {
            warnings.add(new Finding("VERIFICATION_FAILED", WARN,
                    "The address verification estimate could not be read, so this send is going out "
                    + "on send history alone.", e.getMessage()));
            return;
        }

        facts.put("verifiedBouncePct", estimate);
        if (estimate == null) {
            notes.add(new Finding("VERIFICATION_NO_DATA", INFO,
                    "No verification data for this list yet. Run the verifier over it to get a bounce "
                    + "estimate before a large send.", null));
            return;
        }

        String detail = "estimated from " + source.describe();
        if (estimate >= bounceBlockPct) {
            blockers.add(new Finding("VERIFIED_BOUNCE_RISK_HIGH", BLOCK,
                    "Verification puts the expected bounce rate for this list at " + pct(estimate)
                    + ", at or above the " + pct(bounceBlockPct) + " level SES reviews accounts over. "
                    + "Clean the list first.", detail));
        } else if (estimate >= bounceWarnPct) {
            warnings.add(new Finding("VERIFIED_BOUNCE_RISK", WARN,
                    "Verification puts the expected bounce rate for this list at " + pct(estimate) + ".",
                    detail));
        }
    }

    // ------------------------------------------------------------------

    private static String severityOf(String templateSeverity) {
        return switch (templateSeverity == null ? "" : templateSeverity) {
            case "ERROR" -> BLOCK;
            case "WARN" -> WARN;
            default -> INFO;
        };
    }

    private static Double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String pct(double v) { return String.format(Locale.ROOT, "%.2f", v) + "%"; }
}
