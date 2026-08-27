package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The audience snapshot taken when a campaign is queued, plus per-person
 * delivery and engagement. Snapshotting means later list edits never rewrite
 * the history of a send that already happened.
 */
@Entity
@Table(name = "campaign_recipient",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_subscriber",
                columnNames = {"campaignId", "subscriberId"}),
        indexes = {
                @Index(name = "idx_recipient_campaign_status", columnList = "campaignId,status"),
                @Index(name = "idx_recipient_token", columnList = "token", unique = true)
        })
public class CampaignRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Long subscriberId;

    @Column(nullable = false)
    private String email;

    private String name;

    /** PENDING | SENT | FAILED | SKIPPED */
    @Column(nullable = false)
    private String status = "PENDING";

    /** Per-send tracking token, so an open can be attributed to this campaign. */
    @Column(nullable = false, unique = true)
    private String token = UUID.randomUUID().toString();

    private LocalDateTime sentAt;
    private LocalDateTime openedAt;
    private LocalDateTime lastClickedAt;
    private Integer openCount = 0;
    private Integer clickCount = 0;

    @Column(length = 500)
    private String failReason;

    private String messageId;

    public CampaignRecipient() {}

    public CampaignRecipient(Long campaignId, Long subscriberId, String email, String name) {
        this.campaignId = campaignId;
        this.subscriberId = subscriberId;
        this.email = email;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public Long getSubscriberId() { return subscriberId; }
    public String getEmail() { return email; }
    public String getName() { return name == null || name.isBlank() ? "there" : name; }
    public String getStatus() { return status; }
    public String getToken() { return token; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public LocalDateTime getLastClickedAt() { return lastClickedAt; }
    public int getOpenCount() { return openCount == null ? 0 : openCount; }
    public int getClickCount() { return clickCount == null ? 0 : clickCount; }
    public String getFailReason() { return failReason; }
    public String getMessageId() { return messageId; }

    public void setStatus(String v) { this.status = v; }
    public void setSentAt(LocalDateTime v) { this.sentAt = v; }
    public void setOpenedAt(LocalDateTime v) { this.openedAt = v; }
    public void setLastClickedAt(LocalDateTime v) { this.lastClickedAt = v; }
    public void setOpenCount(int v) { this.openCount = v; }
    public void setClickCount(int v) { this.clickCount = v; }
    public void setMessageId(String v) { this.messageId = v; }

    public void setFailReason(String v) {
        this.failReason = v == null || v.length() <= 500 ? v : v.substring(0, 500);
    }
}
