package com.jarurat.mailer.messagelog;

import com.jarurat.mailer.services.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes and searches the message log.
 *
 * Nothing in here is allowed to throw. A send that succeeded must never be
 * reported as failed because the audit write behind it hit a constraint, so
 * every write path swallows and reports to stderr the same way the campaign
 * dispatcher does.
 */
@Service
public class MessageLogService {

    /** Widest sensible window when the caller gives no date bound. Retention keeps the table small anyway. */
    private static final LocalDateTime FLOOR = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final MessageLogRepository repository;
    private final String defaultFromEmail;
    private final int retentionDays;

    public MessageLogService(MessageLogRepository repository,
                             @Value("${aws.ses.fromEmail}") String defaultFromEmail,
                             @Value("${app.messagelog.retentionDays:60}") int retentionDays) {
        this.repository = repository;
        this.defaultFromEmail = defaultFromEmail;
        this.retentionDays = Math.max(1, retentionDays);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /*
     * Deliberately not @Transactional. Neither CampaignService.sendOne nor
     * TransactionalMailService.send runs in a transaction, so each save already
     * commits on its own. Adding REQUIRES_NEW here would take a second pool
     * connection per send for no gain.
     */

    /** The general entry point. The named helpers below are what call sites should normally use. */
    public void record(String direction, String fromEmail, String toEmail, String subject,
                       String messageId, String sesMessageId, Long campaignId, String outcome,
                       String serverResponse, Long latencyMs, String actor) {
        try {
            repository.save(new MessageLogEntry(
                    direction,
                    blank(fromEmail) ? defaultFromEmail : fromEmail.trim().toLowerCase(),
                    toEmail == null ? "" : toEmail.trim().toLowerCase(),
                    subject,
                    messageId,
                    sesMessageId,
                    campaignId,
                    outcome,
                    serverResponse,
                    latencyMs,
                    blank(actor) ? AuditService.currentActor() : actor));
        } catch (Exception e) {
            System.err.println("Message log write dropped for " + toEmail + ": " + e.getMessage());
        }
    }

    /** SES accepted the message. Pass null campaignId for transactional mail. */
    public void recordSent(String toEmail, String subject, String sesMessageId,
                           Long campaignId, long latencyMs, String actor) {
        record("OUTBOUND", null, toEmail, subject, null, sesMessageId, campaignId,
                "SENT", "SES accepted the message", latencyMs, actor);
    }

    /** The send threw. The exception text is the closest thing to a server response we get. */
    public void recordFailed(String toEmail, String subject, Long campaignId,
                             String error, long latencyMs, String actor) {
        record("OUTBOUND", null, toEmail, subject, null, null, campaignId,
                "FAILED", error, latencyMs, actor);
    }

    /** We refused to send. Worth a row, because "nothing happened" is the confusing case to debug. */
    public void recordSuppressed(String toEmail, String subject, Long campaignId,
                                 String reason, String actor) {
        record("OUTBOUND", null, toEmail, subject, null, null, campaignId,
                "SUPPRESSED", reason, null, actor);
    }

    /** Mail that arrived in one of the Stalwart mailboxes. */
    public void recordInbound(String fromEmail, String toEmail, String subject, String messageId) {
        record("INBOUND", fromEmail, toEmail, subject, messageId, null, null,
                "DELIVERED", null, null, "system");
    }

    // ------------------------------------------------------------------
    // Delivery events
    // ------------------------------------------------------------------

    /** How far back a delivery report is allowed to reach for the send it belongs to. */
    private static final int MATCH_WINDOW_HOURS = 6;

    /**
     * One delivery attempt as the mail server recorded it: the verbatim reply from
     * the host that took the message, or refused it.
     */
    public record DeliveryReport(String fromEmail, String toEmail, String host,
                                 int code, String detail, LocalDateTime at, boolean delivered) {}

    /**
     * Upgrades a SENT row with what the receiving server actually said.
     *
     * The source is this box's own Stalwart delivery log, not a payload posted to
     * us, so there is nothing here to forge from outside. Matching is on sender,
     * recipient and a time window because the log line carries no Message-ID; the
     * newest still-open row for that pair wins, and a row that already has a reply
     * is never rewritten.
     *
     * Returns true when a row was touched, so the caller can say whether an event
     * landed anywhere.
     */
    public boolean applyDeliveryReport(DeliveryReport report) {
        try {
            if (report == null || blank(report.toEmail())) return false;
            LocalDateTime at = report.at() == null ? LocalDateTime.now() : report.at();

            // A minute of slack forward: the log timestamp is the delivery attempt
            // and the row was written a moment earlier, but clocks are clocks.
            List<MessageLogEntry> open = repository.findOpenOutbound(
                    nz(report.fromEmail()).toLowerCase(),
                    nz(report.toEmail()).toLowerCase(),
                    at.minusHours(MATCH_WINDOW_HOURS),
                    at.plusMinutes(1),
                    PageRequest.of(0, 1));
            if (open.isEmpty()) return false;

            MessageLogEntry entry = open.get(0);
            entry.setOutcome(report.delivered() ? "DELIVERED" : "BOUNCED");
            entry.setServerResponse(replyText(report));
            entry.setReportingMta(report.host());
            repository.save(entry);
            return true;
        } catch (Exception e) {
            System.err.println("Delivery report not applied to the message log: " + e.getMessage());
            return false;
        }
    }

    /**
     * A delivery this instance never logged, typically a desktop client submitting
     * over SMTP or IMAP rather than through the console. Recorded so the log is
     * genuinely every message that left the box, with the subject left blank
     * because the delivery log does not carry one.
     */
    public void recordObservedDelivery(DeliveryReport report) {
        try {
            MessageLogEntry entry = new MessageLogEntry(
                    "OUTBOUND",
                    nz(report.fromEmail()).toLowerCase(),
                    nz(report.toEmail()).toLowerCase(),
                    null, null, null, null,
                    report.delivered() ? "DELIVERED" : "BOUNCED",
                    replyText(report), null, "system");
            entry.setReportingMta(report.host());
            entry.setTimestamp(report.at() == null ? LocalDateTime.now() : report.at());
            repository.save(entry);
        } catch (Exception e) {
            System.err.println("Observed delivery not logged for " + report.toEmail() + ": " + e.getMessage());
        }
    }

    /** "250 Ok 0109..." reads the way an SMTP transcript reads. Code 0 means the reply had none of its own. */
    private static String replyText(DeliveryReport report) {
        String detail = nz(report.detail());
        if (report.code() <= 0) return detail.isEmpty() ? "No reply text recorded" : detail;
        return detail.isEmpty() ? String.valueOf(report.code()) : report.code() + " " + detail;
    }

    // ------------------------------------------------------------------
    // Searching
    // ------------------------------------------------------------------

    /** Every field is optional. Nulls and blanks mean "do not filter on this". */
    public record Filter(String q, String toAddr, String fromAddr, String subject, String messageId,
                         String direction, String outcome, Long campaignId,
                         LocalDateTime since, LocalDateTime until) {}

    public Page<MessageLogEntry> search(Filter filter, int page, int size) {
        return repository.search(
                nz(filter.q()),
                nz(filter.toAddr()),
                nz(filter.fromAddr()),
                nz(filter.subject()),
                nz(filter.messageId()),
                nz(filter.direction()).toUpperCase(),
                nz(filter.outcome()).toUpperCase(),
                filter.campaignId() == null ? -1L : filter.campaignId(),
                filter.since() == null ? FLOOR : filter.since(),
                filter.until() == null ? LocalDateTime.now().plusDays(1) : filter.until(),
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));
    }

    public MessageLogEntry find(Long id) {
        return repository.findById(id).orElse(null);
    }

    /** Outcome counts for a window, plus the headline numbers the log screen shows. */
    public Map<String, Object> summary(LocalDateTime since, LocalDateTime until) {
        LocalDateTime from = since == null ? LocalDateTime.now().minusDays(1) : since;
        LocalDateTime to = until == null ? LocalDateTime.now().plusDays(1) : until;

        Map<String, Object> counts = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : repository.tallyByOutcome(from, to)) {
            String outcome = row[0] == null ? "UNKNOWN" : row[0].toString();
            long count = ((Number) row[1]).longValue();
            counts.put(outcome, count);
            total += count;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", from.toString());
        out.put("until", to.toString());
        out.put("total", total);
        out.put("byOutcome", counts);
        out.put("retentionDays", retentionDays);
        return out;
    }

    public int getRetentionDays() { return retentionDays; }

    /** Vocabulary the console filter dropdown offers. */
    public List<String> outcomes() {
        return List.of("QUEUED", "SENT", "DELIVERED", "DEFERRED",
                "BOUNCED", "COMPLAINED", "FAILED", "SUPPRESSED");
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    /**
     * 60 days, matching what Postmark and ZeptoMail keep. Long enough that a
     * "did this go out in July" question is answerable, short enough that the
     * table never becomes the largest thing in the database.
     */
    @Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    public void purgeExpired() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int removed = repository.purgeOlderThan(cutoff);
            if (removed > 0) {
                System.out.println("Message log purge removed " + removed
                        + " rows older than " + retentionDays + " days.");
            }
        } catch (Exception e) {
            System.err.println("Message log purge failed: " + e.getMessage());
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
