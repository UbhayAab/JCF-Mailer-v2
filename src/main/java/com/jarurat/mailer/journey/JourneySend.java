package com.jarurat.mailer.journey;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Maps one (node, loop iteration) to the real Campaign that carries its mail.
 *
 * This is the load-bearing decision in the whole engine. Every journey email is sent
 * as an ordinary campaign against ordinary campaign_recipient rows, which means open
 * tracking, click rewriting, the classifier, suppression re-checks, the unsubscribe
 * footer, the message log and every analytics query already work on journey mail with
 * no changes at all. The alternative, a parallel send path, would have meant
 * reimplementing all of that and getting a slightly different answer from each copy.
 *
 * Keying on iteration as well as node is what makes loops legal: campaign_recipient
 * has a unique constraint on (campaignId, subscriberId), so re-firing the same node
 * for the same person needs a different campaign, and the iteration supplies it.
 */
@Entity
@Table(name = "journey_send",
        uniqueConstraints = @UniqueConstraint(name = "uk_send_node_iteration",
                columnNames = {"nodeId", "iterationNo"}),
        indexes = @Index(name = "idx_send_journey", columnList = "journeyId"))
public class JourneySend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long journeyId;

    @Column(nullable = false)
    private Long nodeId;

    @Column(nullable = false)
    private Integer iterationNo;

    @Column(nullable = false)
    private Long campaignId;

    private LocalDateTime createdAt = LocalDateTime.now();

    public JourneySend() {}

    public JourneySend(Long journeyId, Long nodeId, int iterationNo, Long campaignId) {
        this.journeyId = journeyId;
        this.nodeId = nodeId;
        this.iterationNo = iterationNo;
        this.campaignId = campaignId;
    }

    public Long getId() { return id; }
    public Long getJourneyId() { return journeyId; }
    public Long getNodeId() { return nodeId; }
    public int getIterationNo() { return iterationNo == null ? 0 : iterationNo; }
    public Long getCampaignId() { return campaignId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
