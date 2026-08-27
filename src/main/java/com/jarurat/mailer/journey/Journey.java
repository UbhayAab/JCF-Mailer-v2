package com.jarurat.mailer.journey;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A multi-email campaign: a flowchart of base sheets, emails, splits, conditions and
 * loops, plus the safety caps that stop it becoming a mail bomb.
 *
 * The caps are on the journey rather than on any node on purpose. A loop is only safe
 * if something outside the loop bounds it, and a marketer editing one node's delay
 * should not be able to lift the ceiling on the whole thing by accident.
 */
@Entity
@Table(name = "journey", indexes = @Index(name = "idx_journey_status", columnList = "status"))
public class Journey {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    /** DRAFT | ACTIVE | PAUSED | COMPLETED | ABORTED */
    @Column(nullable = false, length = 16)
    private String status = DRAFT;

    /**
     * Bumped by any structural edit. Participants record the version they entered on,
     * so a report can say "these 300 ran the old shape" instead of quietly mixing two
     * different journeys into one set of numbers.
     */
    @Column(nullable = false)
    private Integer definitionVersion = 1;

    private LocalDateTime startAt;

    /** Hard stop. Anyone still moving at this point is exited rather than left hanging. */
    private LocalDateTime deadlineAt;

    // ---------------- safety caps ----------------

    /** No participant receives more than this many emails from this journey, ever. */
    @Column(nullable = false)
    private Integer maxEmailsPerParticipant = 5;

    /** How many times one loop-back edge may fire for one person. */
    @Column(nullable = false)
    private Integer maxLoopIterations = 2;

    /** Minimum hours between two emails to the same person from this journey. */
    @Column(nullable = false)
    private Integer minGapHours = 24;

    /** Nothing is sent between these hours, local time. Shifted forward, never back. */
    @Column(nullable = false)
    private Integer quietStartHour = 21;

    @Column(nullable = false)
    private Integer quietEndHour = 8;

    @Column(nullable = false, length = 64)
    private String zoneId = "Asia/Kolkata";

    /**
     * A tick that would send to more than this many people at once pauses the journey
     * instead. It is the backstop for the whole class of bug where a validation hole
     * lets everybody advance on the same pass.
     */
    @Column(nullable = false)
    private Integer maxSendsPerTick = 500;

    // ---------------- defaults inherited by email nodes ----------------

    private String fromName;
    private String replyTo;

    // ---------------- bookkeeping ----------------

    private LocalDateTime createdAt = LocalDateTime.now();
    private String createdBy;
    private LocalDateTime activatedAt;
    private LocalDateTime completedAt;

    /** Set when a safety cap trips, so the console can explain why it stopped. */
    @Column(length = 400)
    private String pauseReason;

    public Journey() {}

    public Journey(String name, String createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getStatus() { return status; }
    public int getDefinitionVersion() { return definitionVersion == null ? 1 : definitionVersion; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getDeadlineAt() { return deadlineAt; }
    public int getMaxEmailsPerParticipant() { return maxEmailsPerParticipant == null ? 5 : maxEmailsPerParticipant; }
    public int getMaxLoopIterations() { return maxLoopIterations == null ? 2 : maxLoopIterations; }
    public int getMinGapHours() { return minGapHours == null ? 24 : minGapHours; }
    public int getQuietStartHour() { return quietStartHour == null ? 21 : quietStartHour; }
    public int getQuietEndHour() { return quietEndHour == null ? 8 : quietEndHour; }
    public String getZoneId() { return zoneId == null || zoneId.isBlank() ? "Asia/Kolkata" : zoneId; }
    public int getMaxSendsPerTick() { return maxSendsPerTick == null ? 500 : maxSendsPerTick; }
    public String getFromName() { return fromName; }
    public String getReplyTo() { return replyTo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getPauseReason() { return pauseReason; }

    public boolean isRunning() { return ACTIVE.equals(status); }

    /**
     * The shape may only be edited while nobody is in flight. Timing and caps can be
     * changed on a live journey; nodes and edges cannot, because a participant sitting
     * on a node that has just been deleted has nowhere defined to go.
     */
    public boolean isStructurallyEditable() {
        return DRAFT.equals(status) || PAUSED.equals(status);
    }

    public void setName(String v) { this.name = v; }
    public void setDescription(String v) { this.description = v; }
    public void setStatus(String v) { this.status = v; }
    public void setDefinitionVersion(int v) { this.definitionVersion = v; }
    public void setStartAt(LocalDateTime v) { this.startAt = v; }
    public void setDeadlineAt(LocalDateTime v) { this.deadlineAt = v; }
    public void setMaxEmailsPerParticipant(int v) { this.maxEmailsPerParticipant = v; }
    public void setMaxLoopIterations(int v) { this.maxLoopIterations = v; }
    public void setMinGapHours(int v) { this.minGapHours = v; }
    public void setQuietStartHour(int v) { this.quietStartHour = v; }
    public void setQuietEndHour(int v) { this.quietEndHour = v; }
    public void setZoneId(String v) { this.zoneId = v; }
    public void setMaxSendsPerTick(int v) { this.maxSendsPerTick = v; }
    public void setFromName(String v) { this.fromName = v; }
    public void setReplyTo(String v) { this.replyTo = v; }
    public void setActivatedAt(LocalDateTime v) { this.activatedAt = v; }
    public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }
    public void setCreatedBy(String v) { this.createdBy = v; }

    public void setPauseReason(String v) {
        this.pauseReason = v == null || v.length() <= 400 ? v : v.substring(0, 400);
    }
}
