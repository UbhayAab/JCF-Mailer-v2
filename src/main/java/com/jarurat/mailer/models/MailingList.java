package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A reusable audience. Campaigns target a list rather than owning contacts. */
@Entity
@Table(name = "mailing_list")
public class MailingList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** EVENT | NEWSLETTER | IMPORT | HR | OTHER - drives the icon and consent copy. */
    private String kind = "IMPORT";

    private LocalDateTime createdAt = LocalDateTime.now();
    private String createdBy;

    public MailingList() {}

    public MailingList(String name, String description, String kind, String createdBy) {
        this.name = name;
        this.description = description;
        this.kind = kind;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getKind() { return kind; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    public void setName(String v) { this.name = v; }
    public void setDescription(String v) { this.description = v; }
    public void setKind(String v) { this.kind = v; }
}
