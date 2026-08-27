package com.jarurat.mailer.journey;

import com.jarurat.mailer.merge.MergeTags;
import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.repositories.CampaignRepository;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.services.SesSender;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.jarurat.mailer.controllers.AudienceApi.csv;

/**
 * The multi-email campaign API.
 *
 * Permissions reuse the campaign vocabulary rather than adding a journey-specific
 * set: authoring is CAMPAIGNS_WRITE, anything that causes mail to leave is
 * CAMPAIGNS_SEND, reading is CAMPAIGNS_READ, and turning a sheet into an audience is
 * LISTS_WRITE. A new permission would mean editing all five role definitions to say
 * something the existing ones already say correctly.
 */
@RestController
@RequestMapping("/api/journeys")
public class JourneyApi {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final JourneyRepository journeys;
    private final JourneyNodeRepository nodes;
    private final JourneyEdgeRepository edges;
    private final JourneyParticipantRepository participants;
    private final JourneyEventRepository events;
    private final JourneySendRepository sends;
    private final JourneyService service;
    private final JourneyEngine engine;
    private final MailingListRepository lists;
    private final CampaignRepository campaigns;
    private final SesSender ses;
    private final AuditService audit;

    public JourneyApi(JourneyRepository journeys, JourneyNodeRepository nodes,
                      JourneyEdgeRepository edges, JourneyParticipantRepository participants,
                      JourneyEventRepository events, JourneySendRepository sends,
                      JourneyService service, JourneyEngine engine,
                      MailingListRepository lists, CampaignRepository campaigns,
                      SesSender ses, AuditService audit) {
        this.journeys = journeys;
        this.nodes = nodes;
        this.edges = edges;
        this.participants = participants;
        this.events = events;
        this.sends = sends;
        this.service = service;
        this.engine = engine;
        this.lists = lists;
        this.campaigns = campaigns;
        this.ses = ses;
        this.audit = audit;
    }

    // ==================================================================
    // Journeys
    // ==================================================================

    @GetMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Journey j : journeys.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", j.getId());
            row.put("name", j.getName());
            row.put("description", nz(j.getDescription()));
            row.put("status", j.getStatus());
            row.put("nodes", nodes.countByJourneyId(j.getId()));
            row.put("participants", participants.countByJourneyId(j.getId()));
            row.put("active", participants.countByJourneyIdAndState(j.getId(), JourneyParticipant.ACTIVE));
            row.put("createdAt", j.getCreatedAt() == null ? "" : j.getCreatedAt().format(STAMP));
            row.put("createdBy", nz(j.getCreatedBy()));
            row.put("pauseReason", nz(j.getPauseReason()));
            out.add(row);
        }
        return out;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> create(@RequestParam String name,
                                    @RequestParam(required = false) String description) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) return bad("Give the journey a name.");
        if (journeys.existsByName(clean)) return bad("A journey called \"" + clean + "\" already exists.");

        Journey journey = new Journey(clean, AuditService.currentActor());
        journey.setDescription(description);
        journey = journeys.save(journey);
        audit.record("JOURNEY_CREATED", clean, null);
        return ResponseEntity.ok(Map.of("id", journey.getId(), "message", "Journey created."));
    }

    /** The whole flowchart plus live counts, which is one round trip for the canvas. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        Journey journey = journeys.findById(id).orElse(null);
        if (journey == null) return bad("No such journey.");

        Map<String, Long> occupancy = new LinkedHashMap<>();
        for (Object[] row : participants.nodeOccupancy(id)) {
            occupancy.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        List<Map<String, Object>> nodeRows = new ArrayList<>();
        for (JourneyNode n : nodes.findByJourneyIdOrderByStageAscSortOrderAsc(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId());
            row.put("nodeKey", n.getNodeKey());
            row.put("type", n.getType());
            row.put("name", n.getName());
            row.put("x", n.getX());
            row.put("y", n.getY());
            row.put("stage", n.getStage());
            row.put("sourceListId", n.getSourceListId());
            row.put("sourceListName", n.getSourceListId() == null ? null
                    : lists.findById(n.getSourceListId()).map(l -> l.getName()).orElse("(deleted list)"));
            row.put("subject", nz(n.getSubject()));
            row.put("preheader", nz(n.getPreheader()));
            row.put("fromName", nz(n.getFromName()));
            row.put("replyTo", nz(n.getReplyTo()));
            row.put("htmlBody", nz(n.getHtmlBody()));
            row.put("trackOpens", n.isTrackOpens());
            row.put("trackClicks", n.isTrackClicks());
            row.put("delayMinutes", n.getDelayMinutes());
            row.put("absoluteAt", n.getAbsoluteAt() == null ? null : n.getAbsoluteAt().toString());
            row.put("evaluateAfterMinutes", n.getEvaluateAfterMinutes());
            row.put("measuresNodeId", n.getMeasuresNodeId());
            row.put("exitBucket", nz(n.getExitBucket()));
            row.put("here", occupancy.getOrDefault(String.valueOf(n.getId()), 0L));
            row.put("mergeTags", MergeTags.extract(n.getSubject(), n.getPreheader(), n.getHtmlBody()));
            nodeRows.add(row);
        }

        List<Map<String, Object>> edgeRows = new ArrayList<>();
        for (JourneyEdge e : edges.findByJourneyIdOrderBySortOrderAsc(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("from", e.getFromNodeId());
            row.put("to", e.getToNodeId());
            row.put("condition", nz(e.getCondition()));
            row.put("conditionLabel", e.getCondition() == null ? "" : e.conditionType().getLabel());
            row.put("conditionArg", nz(e.getConditionArg()));
            row.put("weight", e.getWeight());
            row.put("armCode", nz(e.getArmCode()));
            row.put("loopBack", e.isLoopBack());
            row.put("exhausted", e.isExhausted());
            edgeRows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey", settingsOf(journey));
        out.put("nodes", nodeRows);
        out.put("edges", edgeRows);
        out.put("stats", service.stats(id));
        out.put("conditions", conditionCatalogue());
        out.put("buckets", bucketCatalogue());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> settingsOf(Journey j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("name", j.getName());
        m.put("description", nz(j.getDescription()));
        m.put("status", j.getStatus());
        m.put("definitionVersion", j.getDefinitionVersion());
        m.put("startAt", j.getStartAt() == null ? null : j.getStartAt().toString());
        m.put("deadlineAt", j.getDeadlineAt() == null ? null : j.getDeadlineAt().toString());
        m.put("maxEmailsPerParticipant", j.getMaxEmailsPerParticipant());
        m.put("maxLoopIterations", j.getMaxLoopIterations());
        m.put("minGapHours", j.getMinGapHours());
        m.put("quietStartHour", j.getQuietStartHour());
        m.put("quietEndHour", j.getQuietEndHour());
        m.put("zoneId", j.getZoneId());
        m.put("maxSendsPerTick", j.getMaxSendsPerTick());
        m.put("fromName", nz(j.getFromName()));
        m.put("replyTo", nz(j.getReplyTo()));
        m.put("structurallyEditable", j.isStructurallyEditable());
        m.put("pauseReason", nz(j.getPauseReason()));
        return m;
    }

    @PostMapping("/{id}/settings")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> settings(@PathVariable Long id,
                                      @RequestParam(required = false) String name,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) String startAt,
                                      @RequestParam(required = false) String deadlineAt,
                                      @RequestParam(required = false) Integer maxEmailsPerParticipant,
                                      @RequestParam(required = false) Integer maxLoopIterations,
                                      @RequestParam(required = false) Integer minGapHours,
                                      @RequestParam(required = false) Integer quietStartHour,
                                      @RequestParam(required = false) Integer quietEndHour,
                                      @RequestParam(required = false) String fromName,
                                      @RequestParam(required = false) String replyTo) {
        try {
            Journey journey = service.require(id);
            if (name != null && !name.isBlank() && !name.trim().equals(journey.getName())) {
                if (journeys.existsByName(name.trim())) return bad("That name is taken.");
                journey.setName(name.trim());
            }
            if (description != null) journey.setDescription(description);
            if (fromName != null) journey.setFromName(blankToNull(fromName));
            if (replyTo != null) journey.setReplyTo(blankToNull(replyTo));

            if (startAt != null) journey.setStartAt(parseTime(startAt));
            if (deadlineAt != null) journey.setDeadlineAt(parseTime(deadlineAt));

            // Caps can be lowered on a running journey but never raised: lowering is
            // the emergency brake and has to work instantly, raising is a change to
            // the promise made when it was activated.
            if (maxEmailsPerParticipant != null) {
                int wanted = Math.max(1, Math.min(50, maxEmailsPerParticipant));
                if (journey.isRunning() && wanted > journey.getMaxEmailsPerParticipant())
                    return bad("The email cap cannot be raised while the journey is running. Pause it first.");
                journey.setMaxEmailsPerParticipant(wanted);
            }
            if (maxLoopIterations != null) {
                int wanted = Math.max(0, Math.min(10, maxLoopIterations));
                if (journey.isRunning() && wanted > journey.getMaxLoopIterations())
                    return bad("The loop cap cannot be raised while the journey is running. Pause it first.");
                journey.setMaxLoopIterations(wanted);
            }
            if (minGapHours != null) journey.setMinGapHours(Math.max(0, Math.min(720, minGapHours)));
            if (quietStartHour != null) journey.setQuietStartHour(clampHour(quietStartHour));
            if (quietEndHour != null) journey.setQuietEndHour(clampHour(quietEndHour));

            journeys.save(journey);
            audit.record("JOURNEY_UPDATED", journey.getName(), null);
            return ResponseEntity.ok(Map.of("message", "Saved.", "journey", settingsOf(journey)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            String name = service.require(id).getName();
            service.deleteJourney(id);
            audit.record("JOURNEY_DELETED", name, null);
            return ResponseEntity.ok(Map.of("message", "Journey deleted."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    // ==================================================================
    // Nodes
    // ==================================================================

    @PostMapping("/{id}/nodes")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> saveNode(@PathVariable Long id,
                                      @RequestParam(required = false) Long nodeId,
                                      @RequestParam(required = false) String type,
                                      @RequestParam(required = false) String name,
                                      @RequestParam(required = false) Integer x,
                                      @RequestParam(required = false) Integer y,
                                      @RequestParam(required = false) Integer stage,
                                      @RequestParam(required = false) Long sourceListId,
                                      @RequestParam(required = false) String subject,
                                      @RequestParam(required = false) String preheader,
                                      @RequestParam(required = false) String fromName,
                                      @RequestParam(required = false) String replyTo,
                                      @RequestParam(required = false) String htmlBody,
                                      @RequestParam(required = false) Boolean trackOpens,
                                      @RequestParam(required = false) Boolean trackClicks,
                                      @RequestParam(required = false) Integer delayMinutes,
                                      @RequestParam(required = false) String absoluteAt,
                                      @RequestParam(required = false) Integer evaluateAfterMinutes,
                                      @RequestParam(required = false) String exitBucket) {
        try {
            Journey journey = service.require(id);
            JourneyNode node;

            if (nodeId != null) {
                node = nodes.findById(nodeId).orElse(null);
                if (node == null || !Objects.equals(node.getJourneyId(), id)) return bad("No such node.");
            } else {
                if (!journey.isStructurallyEditable())
                    return bad("Pause the journey before adding nodes.");
                if (type == null || type.isBlank()) return bad("Say what kind of node this is.");
                node = new JourneyNode(id, mintNodeKey(id, type), type.trim().toUpperCase(Locale.ROOT),
                        name == null || name.isBlank() ? defaultName(type) : name.trim());
                service.bumpVersion(journey);
            }

            if (name != null && !name.isBlank()) node.setName(name.trim());
            if (x != null) node.setX(x);
            if (y != null) node.setY(y);
            if (stage != null) node.setStage(Math.max(1, stage));
            if (sourceListId != null) node.setSourceListId(sourceListId == 0 ? null : sourceListId);
            if (subject != null) node.setSubject(subject);
            if (preheader != null) node.setPreheader(preheader);
            if (fromName != null) node.setFromName(blankToNull(fromName));
            if (replyTo != null) node.setReplyTo(blankToNull(replyTo));
            if (htmlBody != null) node.setHtmlBody(htmlBody);
            if (trackOpens != null) node.setTrackOpens(trackOpens);
            if (trackClicks != null) node.setTrackClicks(trackClicks);
            if (delayMinutes != null) node.setDelayMinutes(Math.max(0, Math.min(525_600, delayMinutes)));
            if (absoluteAt != null) node.setAbsoluteAt(parseTime(absoluteAt));
            if (evaluateAfterMinutes != null)
                node.setEvaluateAfterMinutes(Math.max(1, Math.min(525_600, evaluateAfterMinutes)));
            if (exitBucket != null) node.setExitBucket(blankToNull(exitBucket));

            node = nodes.save(node);
            return ResponseEntity.ok(Map.of("id", node.getId(), "nodeKey", node.getNodeKey(),
                    "message", "Saved."));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/nodes/{nodeId}/delete")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> deleteNode(@PathVariable Long id, @PathVariable Long nodeId) {
        try {
            Journey journey = service.require(id);
            if (!journey.isStructurallyEditable()) return bad("Pause the journey before removing nodes.");
            if (participants.countByJourneyId(id) > 0
                    && participants.nodeOccupancy(id).stream()
                        .anyMatch(row -> String.valueOf(row[0]).equals(String.valueOf(nodeId)))) {
                return bad("People are standing on that node right now. Move them or abort the journey first.");
            }
            edges.deleteTouching(nodeId);
            nodes.deleteById(nodeId);
            service.bumpVersion(journey);
            return ResponseEntity.ok(Map.of("message", "Node removed."));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** Position only. Called on every drag, so it deliberately does not bump the version. */
    @PostMapping("/{id}/nodes/{nodeId}/move")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> moveNode(@PathVariable Long id, @PathVariable Long nodeId,
                                      @RequestParam int x, @RequestParam int y) {
        JourneyNode node = nodes.findById(nodeId).orElse(null);
        if (node == null || !Objects.equals(node.getJourneyId(), id)) return bad("No such node.");
        node.setX(x);
        node.setY(y);
        nodes.save(node);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================================================================
    // Edges
    // ==================================================================

    @PostMapping("/{id}/edges")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> saveEdge(@PathVariable Long id,
                                      @RequestParam(required = false) Long edgeId,
                                      @RequestParam(required = false) Long fromNodeId,
                                      @RequestParam(required = false) Long toNodeId,
                                      @RequestParam(required = false) String condition,
                                      @RequestParam(required = false) String conditionArg,
                                      @RequestParam(required = false) Double weight,
                                      @RequestParam(required = false) String armCode,
                                      @RequestParam(required = false) Boolean loopBack,
                                      @RequestParam(required = false) Boolean exhausted) {
        try {
            Journey journey = service.require(id);
            if (!journey.isStructurallyEditable()) return bad("Pause the journey before rewiring it.");

            JourneyEdge edge;
            if (edgeId != null) {
                edge = edges.findById(edgeId).orElse(null);
                if (edge == null || !Objects.equals(edge.getJourneyId(), id)) return bad("No such connection.");
            } else {
                if (fromNodeId == null || toNodeId == null) return bad("A connection needs both ends.");
                if (Objects.equals(fromNodeId, toNodeId)) return bad("A node cannot point at itself.");
                edge = new JourneyEdge(id, fromNodeId, toNodeId);
            }

            if (fromNodeId != null) edge.setFromNodeId(fromNodeId);
            if (toNodeId != null) edge.setToNodeId(toNodeId);
            if (condition != null) edge.setCondition(blankToNull(condition));
            if (conditionArg != null) edge.setConditionArg(blankToNull(conditionArg));
            if (weight != null) edge.setWeight(Math.max(0, Math.min(1000, weight)));
            if (armCode != null) edge.setArmCode(blankToNull(armCode));
            if (exhausted != null) edge.setExhausted(exhausted);

            // Whether an edge loops is a property of the graph, not an opinion, so it
            // is computed rather than trusted from the caller: an edge pointing at
            // something that can reach it again is a loop however it was drawn.
            boolean computed = loopBack != null ? loopBack : pointsBackwards(id, edge);
            edge.setLoopBack(computed);

            edge = edges.save(edge);
            service.bumpVersion(journey);
            return ResponseEntity.ok(Map.of("id", edge.getId(), "loopBack", edge.isLoopBack(),
                    "message", "Connected."));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** True when the target can already reach the source, which makes this a cycle. */
    private boolean pointsBackwards(Long journeyId, JourneyEdge candidate) {
        Map<Long, List<JourneyEdge>> outgoing = new HashMap<>();
        for (JourneyEdge e : edges.findByJourneyIdOrderBySortOrderAsc(journeyId)) {
            if (Objects.equals(e.getId(), candidate.getId())) continue;
            outgoing.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>()).add(e);
        }
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(candidate.getToNodeId());
        seen.add(candidate.getToNodeId());
        while (!queue.isEmpty()) {
            Long at = queue.poll();
            if (Objects.equals(at, candidate.getFromNodeId())) return true;
            for (JourneyEdge e : outgoing.getOrDefault(at, List.of())) {
                if (seen.add(e.getToNodeId())) queue.add(e.getToNodeId());
            }
        }
        return false;
    }

    @PostMapping("/{id}/edges/{edgeId}/delete")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> deleteEdge(@PathVariable Long id, @PathVariable Long edgeId) {
        try {
            Journey journey = service.require(id);
            if (!journey.isStructurallyEditable()) return bad("Pause the journey before rewiring it.");
            edges.deleteById(edgeId);
            service.bumpVersion(journey);
            return ResponseEntity.ok(Map.of("message", "Disconnected."));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/copy-branch")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> copyBranch(@PathVariable Long id,
                                        @RequestParam Long rootNodeId,
                                        @RequestParam Long attachToNodeId,
                                        @RequestParam(defaultValue = "false") boolean move) {
        try {
            Map<String, Object> result = service.copyBranch(id, rootNodeId, attachToNodeId, move);
            audit.record(move ? "JOURNEY_BRANCH_MOVED" : "JOURNEY_BRANCH_COPIED",
                    service.require(id).getName(), String.valueOf(result));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<?> validate(@PathVariable Long id) {
        try {
            List<JourneyService.Finding> findings = service.validate(id);
            return ResponseEntity.ok(Map.of(
                    "findings", findings,
                    "blocked", JourneyService.blocked(findings)));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> activate(@PathVariable Long id) {
        try {
            List<JourneyService.Finding> findings = service.activate(id);
            boolean blocked = JourneyService.blocked(findings);
            if (!blocked) audit.record("JOURNEY_ACTIVATED", service.require(id).getName(), null);
            return ResponseEntity.ok(Map.of(
                    "findings", findings,
                    "blocked", blocked,
                    "message", blocked
                            ? "This journey cannot start yet. Fix the blocking problems below."
                            : "Journey is live. The first pass runs within a minute."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> pause(@PathVariable Long id, @RequestParam(required = false) String reason) {
        try {
            service.pause(id, reason == null || reason.isBlank()
                    ? "Paused by " + AuditService.currentActor() + "." : reason);
            audit.record("JOURNEY_PAUSED", service.require(id).getName(), reason);
            return ResponseEntity.ok(Map.of("message", "Paused. Nobody will be advanced until you resume."));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        try {
            service.resume(id);
            audit.record("JOURNEY_RESUMED", service.require(id).getName(), null);
            return ResponseEntity.ok(Map.of("message", "Running again."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/{id}/abort")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> abort(@PathVariable Long id) {
        try {
            String name = service.require(id).getName();
            int exited = service.abort(id);
            audit.record("JOURNEY_ABORTED", name, exited + " people let out");
            return ResponseEntity.ok(Map.of("message",
                    "Stopped. " + exited + " people were let out, keeping their current sheet."));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** Forces a pass now instead of waiting for the next minute. */
    @PostMapping("/{id}/run-now")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> runNow(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(engine.runOne(id));
        } catch (RuntimeException e) {
            return bad(String.valueOf(e.getMessage()));
        }
    }

    // ==================================================================
    // Conditional sheets
    // ==================================================================

    @GetMapping("/{id}/sheets")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<?> sheets(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(Map.of("sheets", service.sheets(id)));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @GetMapping("/{id}/sheets/{bucket}/people")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public Map<String, Object> sheetPeople(@PathVariable Long id, @PathVariable String bucket,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        Page<JourneyParticipant> found = participants.findByJourneyIdAndBucketOrderByEnteredAtAsc(
                id, JourneyBucket.parse(bucket).name(),
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JourneyParticipant p : found.getContent()) rows.add(participantRow(p));
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }

    private Map<String, Object> participantRow(JourneyParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("email", p.getEmail());
        m.put("name", nz(p.getName()));
        m.put("state", p.getState());
        m.put("bucket", p.getBucket().name());
        m.put("bucketLabel", p.getBucket().getLabel());
        m.put("emailsSent", p.getEmailsSent());
        m.put("loopCount", p.getLoopCount());
        m.put("variantArm", nz(p.getVariantArm()));
        m.put("enteredAt", p.getEnteredAt() == null ? "" : p.getEnteredAt().format(STAMP));
        m.put("exitReason", nz(p.getExitReason()));
        m.put("nextRunAt", p.getNextRunAt() == null ? null : p.getNextRunAt().format(STAMP));
        return m;
    }

    @GetMapping("/{id}/sheets/{bucket}/export")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public void exportSheet(@PathVariable Long id, @PathVariable String bucket,
                            HttpServletResponse response) throws Exception {
        JourneyBucket target = JourneyBucket.parse(bucket);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"journey-"
                + id + "-" + target.name().toLowerCase(Locale.ROOT) + ".csv\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("Email,Name,Sheet,Emails sent,Loops,Variant,Entered,Exit reason");
            for (JourneyParticipant p : participants.findByJourneyIdAndBucket(id, target.name())) {
                writer.println(String.join(",",
                        csv(p.getEmail()), csv(nz(p.getName())), csv(target.getLabel()),
                        csv(String.valueOf(p.getEmailsSent())), csv(String.valueOf(p.getLoopCount())),
                        csv(nz(p.getVariantArm())),
                        csv(p.getEnteredAt() == null ? "" : p.getEnteredAt().format(STAMP)),
                        csv(nz(p.getExitReason()))));
            }
        }
    }

    @PostMapping("/{id}/sheets/{bucket}/save-as-list")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> saveSheetAsList(@PathVariable Long id, @PathVariable String bucket,
                                             @RequestParam(required = false) String name) {
        try {
            Map<String, Object> result = service.saveSheetAsList(id, bucket, name);
            audit.record("JOURNEY_SHEET_SAVED", String.valueOf(result.get("name")),
                    result.get("added") + " people");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ==================================================================
    // Reporting
    // ==================================================================

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public Map<String, Object> eventLog(@PathVariable Long id,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "60") int size) {
        Page<JourneyEvent> found = events.findByJourneyIdOrderByTimestampDesc(
                id, PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JourneyEvent e : found.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", e.getTimestamp() == null ? "" : e.getTimestamp().format(STAMP));
            row.put("type", e.getEventType());
            row.put("email", nz(e.getEmail()));
            row.put("nodeId", e.getNodeId());
            row.put("detail", nz(e.getDetail()));
            rows.add(row);
        }
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }

    @GetMapping("/{id}/variants/{splitNodeId}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<?> variantReport(@PathVariable Long id, @PathVariable Long splitNodeId) {
        try {
            return ResponseEntity.ok(service.variantReport(id, splitNodeId));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** The campaigns this journey has produced, so its mail is findable in the usual places. */
    @GetMapping("/{id}/campaigns")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public List<Map<String, Object>> journeyCampaigns(@PathVariable Long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JourneySend send : sends.findByJourneyId(id)) {
            Campaign campaign = campaigns.findById(send.getCampaignId()).orElse(null);
            if (campaign == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("campaignId", campaign.getId());
            row.put("nodeId", send.getNodeId());
            row.put("iteration", send.getIterationNo());
            row.put("name", campaign.getName());
            row.put("subject", nz(campaign.getSubject()));
            row.put("sent", campaign.getSentCount());
            row.put("failed", campaign.getFailedCount());
            out.add(row);
        }
        return out;
    }

    /**
     * Sends one node's message to a test address with the caller's own merge values,
     * so the personalisation can be checked before the journey ever runs.
     */
    @PostMapping("/{id}/nodes/{nodeId}/test-send")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> testSendNode(@PathVariable Long id, @PathVariable Long nodeId,
                                          @RequestParam String to,
                                          @RequestParam Map<String, String> allParams) {
        JourneyNode node = nodes.findById(nodeId).orElse(null);
        if (node == null || !Objects.equals(node.getJourneyId(), id)) return bad("No such node.");
        if (!node.isEmail()) return bad("That node does not send anything.");
        if (node.getHtmlBody() == null || node.getHtmlBody().isBlank()) return bad("The body is empty.");

        String recipient = to.trim();
        if (!SesSender.EMAIL_OK.matcher(recipient).matches())
            return bad("That does not look like an email address.");

        Journey journey = service.require(id);
        Map<String, String> merge = new LinkedHashMap<>(MergeTags.sampleMap(
                recipient, node.getSubject(), node.getPreheader(), node.getHtmlBody()));
        merge.putIfAbsent("NAME", "Dr. Akanksha Chichra");
        merge.putIfAbsent("FIRST_NAME", "Dr. Akanksha");
        merge.put("EMAIL", recipient);
        MergeTags.fromParams(allParams, "merge.").forEach((k, v) -> {
            if (v != null && !v.isBlank()) merge.put(k, v);
        });

        try {
            String token = "journeytest-" + UUID.randomUUID();
            String html = ses.renderMarketing(node.getHtmlBody(), token, merge,
                    node.getPreheader(), false, false);
            String subject = "[TEST] " + ses.renderSubject(node.getSubject(), merge);
            String messageId = ses.send(new SesSender.Outgoing(recipient, subject, html,
                    node.getFromName() != null ? node.getFromName() : journey.getFromName(),
                    node.getReplyTo() != null ? node.getReplyTo() : journey.getReplyTo(), null));
            audit.record("JOURNEY_TEST_SENT", journey.getName(), node.getName() + " to " + recipient);
            return ResponseEntity.ok(Map.of("message", "Test of \"" + node.getName()
                    + "\" sent to " + recipient + ".", "messageId", messageId));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "SES rejected it: " + e.getMessage()));
        }
    }

    // ==================================================================
    // Catalogues
    // ==================================================================

    private List<Map<String, Object>> conditionCatalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConditionType c : ConditionType.values()) {
            out.add(Map.of("value", c.name(), "label", c.getLabel(),
                    "description", c.getDescription(), "needsArgument", c.needsArgument(),
                    "bucket", c.getBucket().name()));
        }
        return out;
    }

    private List<Map<String, Object>> bucketCatalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JourneyBucket b : JourneyBucket.values()) {
            if (b == JourneyBucket.NONE) continue;
            out.add(Map.of("value", b.name(), "label", b.getLabel(),
                    "description", b.getDescription(), "terminal", b.mustStop(), "goal", b.isGoal()));
        }
        return out;
    }

    // ==================================================================

    private String mintNodeKey(Long journeyId, String type) {
        String prefix = type.trim().toLowerCase(Locale.ROOT);
        for (int n = 1; n < 10_000; n++) {
            String candidate = prefix + n;
            if (nodes.findByJourneyIdAndNodeKey(journeyId, candidate).isEmpty()) return candidate;
        }
        return prefix + UUID.randomUUID();
    }

    private static String defaultName(String type) {
        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case JourneyNode.SOURCE -> "Base sheet";
            case JourneyNode.EMAIL -> "Email";
            case JourneyNode.SPLIT -> "A/B test";
            case JourneyNode.CONDITION -> "What happened?";
            case JourneyNode.WAIT -> "Wait";
            case JourneyNode.EXIT -> "Exit";
            default -> "Step";
        };
    }

    private static LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Could not read the date and time \"" + raw + "\".");
        }
    }

    private static int clampHour(int hour) { return Math.max(0, Math.min(23, hour)); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(message)));
    }
}
