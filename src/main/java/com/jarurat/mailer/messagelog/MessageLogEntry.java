package com.jarurat.mailer.messagelog;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One row per message that entered or left the system.
 *
 * This is deliberately not campaign_recipient and not transactional_log. Those
 * two answer "how did the campaign perform" and "did the API call succeed".
 * Neither can answer the question support actually gets asked, which is "did
 * that one email to that one person go out, and what did their server say".
 * That question spans both send paths and has to survive the campaign being
 * deleted, so it gets its own flat table.
 *
 * The subject index only serves prefix and equality lookups. The console search
 * uses a leading wildcard, which no btree can satisfy, so subject matching is a
 * sequential scan bounded by the date range. At the volumes this account sends
 * that is cheap; if it stops being cheap the fix is a pg_trgm GIN index, which
 * needs a migration this schema does not have.
 */
@Entity
@Table(name = "message_log", indexes = {
        @Index(name = "idx_msglog_time", columnList = "timestamp"),
        @Index(name = "idx_msglog_to", columnList = "toEmail"),
        @Index(name = "idx_msglog_from", columnList = "fromEmail"),
        @Index(name = "idx_msglog_msgid", columnList = "messageId"),
        @Index(name = "idx_msglog_ses", columnList = "sesMessageId"),
        @Index(name = "idx_msglog_campaign", columnList = "campaignId"),
        @Index(name = "idx_msglog_outcome_time", columnList = "outcome,timestamp"),
        @Index(name = "idx_msglog_subject", columnList = "subject")
})
public class MessageLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp = LocalDateTime.now();

    /** OUTBOUND | INBOUND */
    private String direction;

    private String fromEmail;
    private String toEmail;

    @Column(length = 500)
    private String subject;

    /** The RFC 5322 Message-ID where one exists. Blank for SES API sends, which have no envelope we control. */
    private String messageId;

    /** SES's own opaque id, the only handle that ties a send to a later delivery or bounce event. */
    private String sesMessageId;

    /** Null for transactional and inbound mail. */
    private Long campaignId;

    /**
     * QUEUED | SENT | DELIVERED | DEFERRED | BOUNCED | COMPLAINED | FAILED | SUPPRESSED
     *
     * SENT means SES accepted it. DELIVERED means the recipient's server accepted
     * it, which only SES delivery events can tell us. SUPPRESSED is not in the
     * Postmark vocabulary but is the honest answer when we refused to send at all,
     * and hiding those rows would make the log lie by omission.
     */
    private String outcome;

    /** When the outcome last changed. Sent and delivered are usually seconds apart, sometimes hours. */
    private LocalDateTime outcomeAt = LocalDateTime.now();

    /**
     * Verbatim text from the far end. At send time this is the SES API's rejection
     * message; once SES delivery events are wired it becomes the receiving
     * server's real SMTP reply, e.g. "250 2.0.0 OK  1723... - gsmtp".
     */
    @Column(length = 2000)
    private String serverResponse;

    /** Which MTA produced serverResponse, e.g. "a48-94.smtp-out.amazonses.com". */
    private String reportingMta;

    /** End to end milliseconds around the send call, including any rate limit wait. */
    private Long latencyMs;

    /** Console user's email, or "apikey:<name>", or "system" for scheduled work. */
    private String actor;

    public MessageLogEntry() {}

    public MessageLogEntry(String direction, String fromEmail, String toEmail, String subject,
                           String messageId, String sesMessageId, Long campaignId, String outcome,
                           String serverResponse, Long latencyMs, String actor) {
        this.direction = direction;
        this.fromEmail = fromEmail;
        this.toEmail = toEmail;
        this.subject = clip(subject, 500);
        this.messageId = messageId;
        this.sesMessageId = sesMessageId;
        this.campaignId = campaignId;
        this.outcome = outcome;
        this.serverResponse = clip(serverResponse, 2000);
        this.latencyMs = latencyMs;
        this.actor = actor;
    }

    public Long getId() { return id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getDirection() { return direction; }
    public String getFromEmail() { return fromEmail; }
    public String getToEmail() { return toEmail; }
    public String getSubject() { return subject; }
    public String getMessageId() { return messageId; }
    public String getSesMessageId() { return sesMessageId; }
    public Long getCampaignId() { return campaignId; }
    public String getOutcome() { return outcome; }
    public LocalDateTime getOutcomeAt() { return outcomeAt; }
    public String getServerResponse() { return serverResponse; }
    public String getReportingMta() { return reportingMta; }
    public Long getLatencyMs() { return latencyMs; }
    public String getActor() { return actor; }

    // Only the fields a later delivery event is allowed to revise. Everything
    // else is what happened at send time and must not be rewritten.
    public void setOutcome(String outcome) {
        this.outcome = outcome;
        this.outcomeAt = LocalDateTime.now();
    }

    public void setServerResponse(String serverResponse) { this.serverResponse = clip(serverResponse, 2000); }
    public void setReportingMta(String reportingMta) { this.reportingMta = clip(reportingMta, 255); }
    public void setSesMessageId(String sesMessageId) { this.sesMessageId = sesMessageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    static String clip(String s, int max) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
