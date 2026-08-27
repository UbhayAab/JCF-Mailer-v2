package com.jarurat.mailer.analytics;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One row per pixel load and per link follow, with the classifier's verdict and the
 * raw signals that produced it.
 *
 * The raw signals are the point of this table. CampaignRecipient only keeps a counter,
 * so once a hit is counted there the decision is permanent. Keeping userAgent, ip and
 * secondsSinceSent means a better classifier can be run back over the whole history and
 * every published number recomputed, instead of history becoming uncomparable the day
 * the rules change.
 */
@Entity
@Table(name = "tracking_event", indexes = {
        @Index(name = "idx_track_campaign", columnList = "campaignId"),
        @Index(name = "idx_track_time", columnList = "timestamp"),
        @Index(name = "idx_track_kind", columnList = "eventType,classification")
})
public class TrackingEvent {

    public static final String OPEN = "OPEN";
    public static final String CLICK = "CLICK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OPEN | CLICK */
    @Column(nullable = false, length = 16)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OpenClassification classification;

    private Long campaignId;
    private Long subscriberId;
    private Long recipientId;
    private String email;

    @Column(length = 1000)
    private String url;

    @Column(length = 500)
    private String userAgent;

    @Column(length = 64)
    private String ip;

    /** Best guess at the mail client, kept denormalised so the breakdown is a group by. */
    @Column(length = 64)
    private String client;

    @Column(length = 32)
    private String deviceType;

    /** Null when the send time was unknown, which is not the same as zero. */
    private Long secondsSinceSent;

    /** Why the classifier landed where it did, so a surprising number can be explained. */
    @Column(length = 200)
    private String reason;

    private LocalDateTime timestamp = LocalDateTime.now();

    public TrackingEvent() {}

    public TrackingEvent(String eventType, OpenClassification classification,
                         Long campaignId, Long subscriberId, Long recipientId, String email,
                         String url, String userAgent, String ip,
                         String client, String deviceType, Long secondsSinceSent, String reason) {
        this.eventType = eventType;
        this.classification = classification;
        this.campaignId = campaignId;
        this.subscriberId = subscriberId;
        this.recipientId = recipientId;
        this.email = email;
        this.url = trim(url, 1000);
        this.userAgent = trim(userAgent, 500);
        this.ip = trim(ip, 64);
        this.client = trim(client, 64);
        this.deviceType = trim(deviceType, 32);
        this.secondsSinceSent = secondsSinceSent;
        this.reason = trim(reason, 200);
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public OpenClassification getClassification() { return classification; }
    public Long getCampaignId() { return campaignId; }
    public Long getSubscriberId() { return subscriberId; }
    public Long getRecipientId() { return recipientId; }
    public String getEmail() { return email; }
    public String getUrl() { return url; }
    public String getUserAgent() { return userAgent; }
    public String getIp() { return ip; }
    public String getClient() { return client; }
    public String getDeviceType() { return deviceType; }
    public Long getSecondsSinceSent() { return secondsSinceSent; }
    public String getReason() { return reason; }
    public LocalDateTime getTimestamp() { return timestamp; }

    /** Set only by the reclassification pass, which rewrites verdicts from stored signals. */
    public void setClassification(OpenClassification classification) { this.classification = classification; }
    public void setClient(String client) { this.client = trim(client, 64); }
    public void setDeviceType(String deviceType) { this.deviceType = trim(deviceType, 32); }
    public void setReason(String reason) { this.reason = trim(reason, 200); }
}
