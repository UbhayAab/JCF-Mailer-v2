package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** One row per click, so per-link performance is answerable. */
@Entity
@Table(name = "click_event", indexes = @Index(name = "idx_click_campaign", columnList = "campaignId"))
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long campaignId;
    private Long subscriberId;
    private String email;

    @Column(length = 1000)
    private String url;

    private LocalDateTime timestamp = LocalDateTime.now();

    public ClickEvent() {}

    public ClickEvent(Long campaignId, Long subscriberId, String email, String url) {
        this.campaignId = campaignId;
        this.subscriberId = subscriberId;
        this.email = email;
        this.url = url == null || url.length() <= 1000 ? url : url.substring(0, 1000);
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public Long getSubscriberId() { return subscriberId; }
    public String getEmail() { return email; }
    public String getUrl() { return url; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
