package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign", indexes = @Index(name = "idx_campaign_status", columnList = "status"))
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String subject;
    private String preheader;
    private String fromName;
    private String replyTo;

    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    /** Audience. Null means the campaign has not been pointed at a list yet. */
    private Long listId;

    /** DRAFT | SCHEDULED | SENDING | SENT | PAUSED | FAILED | CANCELLED */
    @Column(nullable = false)
    private String status = "DRAFT";

    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt = LocalDateTime.now();
    private String createdBy;

    private Integer totalRecipients = 0;
    private Integer sentCount = 0;
    private Integer failedCount = 0;

    /** Marketing mail gets an unsubscribe footer and tracking; transactional does not. */
    private boolean trackOpens = true;
    private boolean trackClicks = true;

    public Campaign() {}

    public Campaign(String name, String createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSubject() { return subject; }
    public String getPreheader() { return preheader; }
    public String getFromName() { return fromName; }
    public String getReplyTo() { return replyTo; }
    public String getHtmlBody() { return htmlBody; }
    public Long getListId() { return listId; }
    public String getStatus() { return status; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public int getTotalRecipients() { return totalRecipients == null ? 0 : totalRecipients; }
    public int getSentCount() { return sentCount == null ? 0 : sentCount; }
    public int getFailedCount() { return failedCount == null ? 0 : failedCount; }
    public boolean isTrackOpens() { return trackOpens; }
    public boolean isTrackClicks() { return trackClicks; }

    public boolean isEditable() {
        return "DRAFT".equals(status) || "SCHEDULED".equals(status) || "FAILED".equals(status);
    }

    public void setName(String v) { this.name = v; }
    public void setSubject(String v) { this.subject = v; }
    public void setPreheader(String v) { this.preheader = v; }
    public void setFromName(String v) { this.fromName = v; }
    public void setReplyTo(String v) { this.replyTo = v; }
    public void setHtmlBody(String v) { this.htmlBody = v; }
    public void setListId(Long v) { this.listId = v; }
    public void setStatus(String v) { this.status = v; }
    public void setScheduledAt(LocalDateTime v) { this.scheduledAt = v; }
    public void setStartedAt(LocalDateTime v) { this.startedAt = v; }
    public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public void setTotalRecipients(int v) { this.totalRecipients = v; }
    public void setSentCount(int v) { this.sentCount = v; }
    public void setFailedCount(int v) { this.failedCount = v; }
    public void setTrackOpens(boolean v) { this.trackOpens = v; }
    public void setTrackClicks(boolean v) { this.trackClicks = v; }
}
