package com.jarurat.mailer.journey;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One box on the canvas.
 *
 * The email fields sit on the node as real columns rather than inside a config blob
 * because they are what the composer edits, what validation reads and what the send
 * path renders. A JSON column would mean three separate pieces of code parsing the
 * same string and disagreeing about it on the day someone types a stray quote.
 */
@Entity
@Table(name = "journey_node",
        uniqueConstraints = @UniqueConstraint(name = "uk_node_key", columnNames = {"journeyId", "nodeKey"}),
        indexes = @Index(name = "idx_node_journey", columnList = "journeyId"))
public class JourneyNode {

    public static final String SOURCE = "SOURCE";
    public static final String EMAIL = "EMAIL";
    public static final String SPLIT = "SPLIT";
    public static final String CONDITION = "CONDITION";
    public static final String WAIT = "WAIT";
    public static final String EXIT = "EXIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long journeyId;

    /**
     * Stable within a journey and never reused. Copying a branch mints new keys, which
     * is what keeps a copied subtree's history separate from the original's.
     */
    @Column(nullable = false, length = 64)
    private String nodeKey;

    /** SOURCE | EMAIL | SPLIT | CONDITION | WAIT | EXIT */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false)
    private String name;

    /** Canvas position. Presentation only; the graph lives in journey_edge. */
    private Integer x = 0;
    private Integer y = 0;

    /** Rank on the canvas, which is what the UI calls "stage". */
    private Integer stage = 1;

    private Integer sortOrder = 0;

    // ---------------- SOURCE ----------------

    /** The base sheet. No foreign key: a deleted list must warn, not break the journey. */
    private Long sourceListId;

    // ---------------- EMAIL ----------------

    private String subject;
    private String preheader;
    private String fromName;
    private String replyTo;

    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    private Boolean trackOpens = Boolean.TRUE;
    private Boolean trackClicks = Boolean.TRUE;

    // ---------------- WAIT and EMAIL timing ----------------

    /**
     * Minutes to wait after the parent step reached THIS person, not after the journey
     * started. Per-participant clocks are the whole point: "48h after stage 1 reached
     * you" is a different instant for someone admitted on Tuesday than for someone
     * admitted on Friday.
     */
    private Integer delayMinutes = 0;

    /** Set instead of delayMinutes when the marketer wants a fixed wall-clock moment. */
    private LocalDateTime absoluteAt;

    // ---------------- CONDITION ----------------

    /**
     * How long after this person's measured send the condition is judged. A condition
     * evaluated too early reads every slow reader as a non-opener.
     */
    private Integer evaluateAfterMinutes = 2880; // 48 hours

    /**
     * Which email node's outcome is being measured. Null means "the nearest email
     * ancestor", resolved at activation and pinned so a later edit cannot silently
     * repoint a running condition at a different message.
     */
    private Long measuresNodeId;

    // ---------------- EXIT ----------------

    /** The sheet a person is filed under when they leave through this node. */
    @Column(length = 32)
    private String exitBucket;

    private LocalDateTime createdAt = LocalDateTime.now();

    public JourneyNode() {}

    public JourneyNode(Long journeyId, String nodeKey, String type, String name) {
        this.journeyId = journeyId;
        this.nodeKey = nodeKey;
        this.type = type;
        this.name = name;
    }

    public Long getId() { return id; }
    public Long getJourneyId() { return journeyId; }
    public String getNodeKey() { return nodeKey; }
    public String getType() { return type; }
    public String getName() { return name; }
    public int getX() { return x == null ? 0 : x; }
    public int getY() { return y == null ? 0 : y; }
    public int getStage() { return stage == null ? 1 : stage; }
    public int getSortOrder() { return sortOrder == null ? 0 : sortOrder; }
    public Long getSourceListId() { return sourceListId; }
    public String getSubject() { return subject; }
    public String getPreheader() { return preheader; }
    public String getFromName() { return fromName; }
    public String getReplyTo() { return replyTo; }
    public String getHtmlBody() { return htmlBody; }
    public boolean isTrackOpens() { return trackOpens == null || trackOpens; }
    public boolean isTrackClicks() { return trackClicks == null || trackClicks; }
    public int getDelayMinutes() { return delayMinutes == null ? 0 : delayMinutes; }
    public LocalDateTime getAbsoluteAt() { return absoluteAt; }
    public int getEvaluateAfterMinutes() { return evaluateAfterMinutes == null ? 2880 : evaluateAfterMinutes; }
    public Long getMeasuresNodeId() { return measuresNodeId; }
    public String getExitBucket() { return exitBucket; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public boolean isEmail() { return EMAIL.equals(type); }
    public boolean isSource() { return SOURCE.equals(type); }
    public boolean isCondition() { return CONDITION.equals(type); }
    public boolean isSplit() { return SPLIT.equals(type); }
    public boolean isExit() { return EXIT.equals(type); }
    public boolean isWait() { return WAIT.equals(type); }

    public void setNodeKey(String v) { this.nodeKey = v; }
    public void setType(String v) { this.type = v; }
    public void setName(String v) { this.name = v; }
    public void setX(int v) { this.x = v; }
    public void setY(int v) { this.y = v; }
    public void setStage(int v) { this.stage = v; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public void setSourceListId(Long v) { this.sourceListId = v; }
    public void setSubject(String v) { this.subject = v; }
    public void setPreheader(String v) { this.preheader = v; }
    public void setFromName(String v) { this.fromName = v; }
    public void setReplyTo(String v) { this.replyTo = v; }
    public void setHtmlBody(String v) { this.htmlBody = v; }
    public void setTrackOpens(boolean v) { this.trackOpens = v; }
    public void setTrackClicks(boolean v) { this.trackClicks = v; }
    public void setDelayMinutes(int v) { this.delayMinutes = v; }
    public void setAbsoluteAt(LocalDateTime v) { this.absoluteAt = v; }
    public void setEvaluateAfterMinutes(int v) { this.evaluateAfterMinutes = v; }
    public void setMeasuresNodeId(Long v) { this.measuresNodeId = v; }
    public void setExitBucket(String v) { this.exitBucket = v; }
}
