package com.jarurat.mailer.journey;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Why one person moved. The question this table exists to answer is "why did this
 * doctor not get the second email", and answering it after the fact is impossible
 * from state alone: the participant row shows where they ended, never the branch
 * they were refused or the cap that stopped them.
 */
@Entity
@Table(name = "journey_event", indexes = {
        @Index(name = "idx_jevent_journey", columnList = "journeyId,timestamp"),
        @Index(name = "idx_jevent_participant", columnList = "participantId")
})
public class JourneyEvent {

    public static final String ENTERED = "ENTERED";
    public static final String SENT = "SENT";
    public static final String SEND_FAILED = "SEND_FAILED";
    public static final String ADVANCED = "ADVANCED";
    public static final String BUCKETED = "BUCKETED";
    public static final String LOOPED = "LOOPED";
    public static final String EXITED = "EXITED";
    public static final String CAPPED = "CAPPED";
    public static final String DEFERRED = "DEFERRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long journeyId;

    private Long participantId;
    private Long nodeId;
    private String email;

    @Column(nullable = false, length = 24)
    private String eventType;

    /** The branch taken, the cap hit, or the reason a step was refused. */
    @Column(length = 400)
    private String detail;

    private LocalDateTime timestamp = LocalDateTime.now();

    public JourneyEvent() {}

    public JourneyEvent(Long journeyId, Long participantId, Long nodeId, String email,
                        String eventType, String detail) {
        this.journeyId = journeyId;
        this.participantId = participantId;
        this.nodeId = nodeId;
        this.email = email;
        this.eventType = eventType;
        this.detail = detail == null || detail.length() <= 400 ? detail : detail.substring(0, 400);
    }

    public Long getId() { return id; }
    public Long getJourneyId() { return journeyId; }
    public Long getParticipantId() { return participantId; }
    public Long getNodeId() { return nodeId; }
    public String getEmail() { return email; }
    public String getEventType() { return eventType; }
    public String getDetail() { return detail; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
