package com.jarurat.mailer.journey;

import jakarta.persistence.*;

/**
 * One arrow. What it means depends on the node it leaves:
 *
 *   from a SPLIT      - one A/B arm, and weight is its share
 *   from a CONDITION  - one branch, and condition names which outcome routes here
 *   anything else     - the single next step, condition null
 *
 * loopBack marks an edge that points at an ancestor. It is stored as a flag rather
 * than derived, because the executor has to count iterations per participant per edge
 * and needs to know it is looping before it walks the graph, not after.
 */
@Entity
@Table(name = "journey_edge", indexes = {
        @Index(name = "idx_edge_journey", columnList = "journeyId"),
        @Index(name = "idx_edge_from", columnList = "fromNodeId")
})
public class JourneyEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long journeyId;

    @Column(nullable = false)
    private Long fromNodeId;

    @Column(nullable = false)
    private Long toNodeId;

    /** ConditionType name when leaving a CONDITION, otherwise null. */
    @Column(length = 32)
    private String condition;

    /** Only meaningful for CLICKED_SPECIFIC. */
    @Column(length = 1000)
    private String conditionArg;

    /**
     * Share of a SPLIT, as typed. Weights are normalised at assignment time rather
     * than validated to 100, because a marketer typing 40/40/30 should see the
     * effective split update, not a dialog telling them to do arithmetic.
     */
    private Double weight = 1.0;

    /** A, B, C on a split arm. Shown on the canvas and carried into reporting. */
    @Column(length = 4)
    private String armCode;

    @Column(nullable = false)
    private Boolean loopBack = Boolean.FALSE;

    /**
     * Fires when a loop has run out of iterations. Every loop must have exactly one,
     * so a person who never converges leaves through a defined door rather than being
     * stopped by a cap check with nowhere to go.
     */
    @Column(nullable = false)
    private Boolean exhausted = Boolean.FALSE;

    private Integer sortOrder = 0;

    public JourneyEdge() {}

    public JourneyEdge(Long journeyId, Long fromNodeId, Long toNodeId) {
        this.journeyId = journeyId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
    }

    public Long getId() { return id; }
    public Long getJourneyId() { return journeyId; }
    public Long getFromNodeId() { return fromNodeId; }
    public Long getToNodeId() { return toNodeId; }
    public String getCondition() { return condition; }
    public String getConditionArg() { return conditionArg; }
    public double getWeight() { return weight == null || weight < 0 ? 0 : weight; }
    public String getArmCode() { return armCode; }
    public boolean isLoopBack() { return loopBack != null && loopBack; }
    public boolean isExhausted() { return exhausted != null && exhausted; }
    public int getSortOrder() { return sortOrder == null ? 0 : sortOrder; }

    public ConditionType conditionType() { return ConditionType.parse(condition); }

    public void setFromNodeId(Long v) { this.fromNodeId = v; }
    public void setToNodeId(Long v) { this.toNodeId = v; }
    public void setCondition(String v) { this.condition = v; }
    public void setConditionArg(String v) { this.conditionArg = v; }
    public void setWeight(double v) { this.weight = v; }
    public void setArmCode(String v) { this.armCode = v; }
    public void setLoopBack(boolean v) { this.loopBack = v; }
    public void setExhausted(boolean v) { this.exhausted = v; }
    public void setSortOrder(int v) { this.sortOrder = v; }
}
