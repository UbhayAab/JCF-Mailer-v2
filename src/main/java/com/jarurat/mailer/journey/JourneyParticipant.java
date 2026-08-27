package com.jarurat.mailer.journey;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One person's position in one journey. This row is both the state machine and the
 * conditional sheet: `bucket` is which sheet they are on, so the sheets are always
 * consistent with the flow by construction rather than by a second table that has to
 * be kept in step.
 *
 * The unique constraint on (journeyId, subscriberId) is what makes "the same doctor is
 * on both base sheets" harmless. They are admitted once, by whichever source has the
 * lower sort order, and the other source records them as an overlap for the report.
 */
@Entity
@Table(name = "journey_participant",
        uniqueConstraints = @UniqueConstraint(name = "uk_journey_subscriber",
                columnNames = {"journeyId", "subscriberId"}),
        indexes = {
                @Index(name = "idx_jp_due", columnList = "state,nextRunAt"),
                @Index(name = "idx_jp_journey_bucket", columnList = "journeyId,bucket"),
                @Index(name = "idx_jp_subscriber", columnList = "subscriberId"),
                @Index(name = "idx_jp_email", columnList = "email")
        })
public class JourneyParticipant {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXITED = "EXITED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long journeyId;

    @Column(nullable = false)
    private Long subscriberId;

    @Column(nullable = false)
    private String email;

    private String name;

    /** Which base sheet let them in. */
    private Long sourceNodeId;

    /** Where they are standing right now. Null once exited. */
    private Long currentNodeId;

    /** ACTIVE | EXITED | FAILED */
    @Column(nullable = false, length = 16)
    private String state = ACTIVE;

    /** The conditional sheet they are on. Only ever moves up the rank ladder. */
    @Column(nullable = false, length = 32)
    private String bucket = JourneyBucket.NONE.name();

    /** Denormalised so the promotion check is a comparison rather than an enum parse. */
    @Column(nullable = false)
    private Integer bucketRank = 0;

    private LocalDateTime bucketAt;

    /**
     * Which pass round a loop they are on. Part of the key that campaigns are
     * materialised under, so iteration 2 of a nudge is a different campaign from
     * iteration 1 and the unique constraint on campaign_recipient does not block it.
     */
    @Column(nullable = false)
    private Integer iterationNo = 0;

    /** How many times the loop-back edge has fired for this person. */
    @Column(nullable = false)
    private Integer loopCount = 0;

    @Column(nullable = false)
    private Integer emailsSent = 0;

    /** When the executor should look at this person next. Null means never. */
    private LocalDateTime nextRunAt;

    private LocalDateTime lastSendAt;

    /** The recipient row of the message a pending condition is measuring. */
    private Long measuredRecipientId;

    /** Which A/B arm they were assigned, kept for reporting and for stickiness. */
    @Column(length = 4)
    private String variantArm;

    private Long variantNodeId;

    /** The version of the flowchart they entered on. */
    @Column(nullable = false)
    private Integer definitionVersion = 1;

    private LocalDateTime enteredAt = LocalDateTime.now();
    private LocalDateTime exitedAt;

    @Column(length = 64)
    private String exitReason;

    /**
     * Optimistic lock. Two ticks overlapping, or a restart mid-tick, must not advance
     * one person twice: the version check turns the second write into a no-op rather
     * than a duplicate send.
     */
    @Version
    private Long version;

    /** How many consecutive times a send for this person has failed. */
    @Column(nullable = false)
    private Integer failureCount = 0;

    @Column(length = 400)
    private String lastError;

    public JourneyParticipant() {}

    public JourneyParticipant(Long journeyId, Long subscriberId, String email, String name,
                              Long sourceNodeId, int definitionVersion) {
        this.journeyId = journeyId;
        this.subscriberId = subscriberId;
        this.email = email;
        this.name = name;
        this.sourceNodeId = sourceNodeId;
        this.currentNodeId = sourceNodeId;
        this.definitionVersion = definitionVersion;
    }

    public Long getId() { return id; }
    public Long getJourneyId() { return journeyId; }
    public Long getSubscriberId() { return subscriberId; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public Long getSourceNodeId() { return sourceNodeId; }
    public Long getCurrentNodeId() { return currentNodeId; }
    public String getState() { return state; }
    public JourneyBucket getBucket() { return JourneyBucket.parse(bucket); }
    public int getBucketRank() { return bucketRank == null ? 0 : bucketRank; }
    public LocalDateTime getBucketAt() { return bucketAt; }
    public int getIterationNo() { return iterationNo == null ? 0 : iterationNo; }
    public int getLoopCount() { return loopCount == null ? 0 : loopCount; }
    public int getEmailsSent() { return emailsSent == null ? 0 : emailsSent; }
    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public LocalDateTime getLastSendAt() { return lastSendAt; }
    public Long getMeasuredRecipientId() { return measuredRecipientId; }
    public String getVariantArm() { return variantArm; }
    public Long getVariantNodeId() { return variantNodeId; }
    public int getDefinitionVersion() { return definitionVersion == null ? 1 : definitionVersion; }
    public LocalDateTime getEnteredAt() { return enteredAt; }
    public LocalDateTime getExitedAt() { return exitedAt; }
    public String getExitReason() { return exitReason; }
    public int getFailureCount() { return failureCount == null ? 0 : failureCount; }
    public String getLastError() { return lastError; }

    public boolean isActive() { return ACTIVE.equals(state); }

    /**
     * Moves the person up the sheet ladder, and takes them out of the flow when the
     * new sheet is terminal. Returns true when the sheet actually changed, so the
     * caller only writes an event row for a real movement.
     */
    public boolean promoteBucket(JourneyBucket candidate, LocalDateTime when) {
        if (candidate == null || candidate.getRank() <= getBucketRank()) return false;
        this.bucket = candidate.name();
        this.bucketRank = candidate.getRank();
        this.bucketAt = when;
        // A goal sheet does not exit on its own: the flowchart decides whether
        // clicking ends the conversation. An unsubscribe, bounce or complaint does,
        // because continuing past one of those is not the marketer's call to make.
        if (candidate.mustStop()) exit("BUCKET_" + candidate.name(), when);
        return true;
    }

    public void exit(String reason, LocalDateTime when) {
        this.state = EXITED;
        this.exitReason = reason == null || reason.length() <= 64 ? reason : reason.substring(0, 64);
        this.exitedAt = when;
        this.nextRunAt = null;
        this.currentNodeId = null;
    }

    public void setCurrentNodeId(Long v) { this.currentNodeId = v; }
    public void setState(String v) { this.state = v; }
    public void setIterationNo(int v) { this.iterationNo = v; }
    public void setLoopCount(int v) { this.loopCount = v; }
    public void setEmailsSent(int v) { this.emailsSent = v; }
    public void setNextRunAt(LocalDateTime v) { this.nextRunAt = v; }
    public void setLastSendAt(LocalDateTime v) { this.lastSendAt = v; }
    public void setMeasuredRecipientId(Long v) { this.measuredRecipientId = v; }
    public void setVariantArm(String v) { this.variantArm = v; }
    public void setVariantNodeId(Long v) { this.variantNodeId = v; }
    public void setFailureCount(int v) { this.failureCount = v; }
    public void setName(String v) { this.name = v; }

    public void setLastError(String v) {
        this.lastError = v == null || v.length() <= 400 ? v : v.substring(0, 400);
    }
}
