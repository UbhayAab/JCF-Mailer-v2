package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Delivery record for one-off API sends, kept apart from campaign reporting. */
@Entity
@Table(name = "transactional_log", indexes = {
        @Index(name = "idx_txn_time", columnList = "timestamp"),
        @Index(name = "idx_txn_slug", columnList = "templateSlug")
})
public class TransactionalLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * These three are clipped on the way in rather than trusted.
     *
     * They arrive straight off an API request body, and with no length declared
     * Hibernate maps them to varchar(255). A caller sending a 300 character slug or
     * recipient made the insert throw a DataIntegrityViolationException out of the
     * logging call, which surfaced as an HTTP 500 on what should have been a plain
     * 400. Logging a rejected request must never be able to fail harder than the
     * request it is logging.
     */
    @Column(length = 200)
    private String templateSlug;

    @Column(length = 254)
    private String toEmail;

    @Column(length = 500)
    private String subject;

    /** SENT | FAILED | SUPPRESSED */
    @Column(length = 16)
    private String status;

    @Column(length = 255)
    private String messageId;

    @Column(length = 500)
    private String error;

    /** Which credential sent it: an API key name, or a console user's email. */
    @Column(length = 160)
    private String sentVia;

    private LocalDateTime timestamp = LocalDateTime.now();

    public TransactionalLog() {}

    public TransactionalLog(String templateSlug, String toEmail, String subject,
                            String status, String messageId, String error, String sentVia) {
        this.templateSlug = clip(templateSlug, 200);
        this.toEmail = clip(toEmail, 254);
        this.subject = clip(subject, 500);
        this.status = clip(status, 16);
        this.messageId = clip(messageId, 255);
        this.error = clip(error, 500);
        this.sentVia = clip(sentVia, 160);
    }

    /**
     * Clipped in the constructor, not just declared on the column, so an oversized
     * value becomes a truncated log row rather than an exception thrown from inside
     * the error handler. MessageLogEntry has done this for a while; this class was
     * the one that had not caught up.
     */
    private static String clip(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public String getTemplateSlug() { return templateSlug; }
    public String getToEmail() { return toEmail; }
    public String getSubject() { return subject; }
    public String getStatus() { return status; }
    public String getMessageId() { return messageId; }
    public String getError() { return error; }
    public String getSentVia() { return sentVia; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
