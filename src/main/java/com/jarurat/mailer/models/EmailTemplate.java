package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Reusable creative. MARKETING templates get pulled into campaigns; TRANSACTIONAL
 * ones are addressed by slug from the API, which is how the HR system fires an
 * interview mail without anyone opening the console.
 */
@Entity
@Table(name = "email_template")
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Stable machine identifier used by POST /api/v1/transactional/send. */
    @Column(nullable = false, unique = true)
    private String slug;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    /** MARKETING | TRANSACTIONAL */
    @Column(nullable = false)
    private String type = "MARKETING";

    private String description;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private String createdBy;

    public EmailTemplate() {}

    public EmailTemplate(String name, String slug, String subject, String htmlBody,
                         String type, String createdBy) {
        this.name = name;
        this.slug = slug;
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.type = type;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getSubject() { return subject; }
    public String getHtmlBody() { return htmlBody; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }

    public boolean isTransactional() { return "TRANSACTIONAL".equals(type); }

    public void setName(String v) { this.name = v; }
    public void setSlug(String v) { this.slug = v; }
    public void setSubject(String v) { this.subject = v; }
    public void setHtmlBody(String v) { this.htmlBody = v; }
    public void setType(String v) { this.type = v; }
    public void setDescription(String v) { this.description = v; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }

    /** "Interview Round 1" -> "interview-round-1" */
    public static String slugify(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return s.isEmpty() ? "template" : s;
    }
}
