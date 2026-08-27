package com.jarurat.mailer.journey;

import com.jarurat.mailer.models.MailingList;
import com.jarurat.mailer.models.ListMember;
import com.jarurat.mailer.repositories.ListMemberRepository;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.services.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Everything about a journey that is not the tick: building the graph, checking it is
 * safe to run, copying a branch onto a second base sheet, and reading the conditional
 * sheets back out.
 *
 * Validation is the part that matters most. An unattended engine that sends mail on a
 * schedule for days has no operator watching each message go out, so the moment to
 * catch "this loop has no way out" is before activation, not at three in the morning
 * on the fourth pass.
 */
@Service
public class JourneyService {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";

    private final JourneyRepository journeys;
    private final JourneyNodeRepository nodes;
    private final JourneyEdgeRepository edges;
    private final JourneyParticipantRepository participants;
    private final JourneySendRepository sends;
    private final JourneyEventRepository events;
    private final MailingListRepository lists;
    private final ListMemberRepository listMembers;

    public JourneyService(JourneyRepository journeys,
                          JourneyNodeRepository nodes,
                          JourneyEdgeRepository edges,
                          JourneyParticipantRepository participants,
                          JourneySendRepository sends,
                          JourneyEventRepository events,
                          MailingListRepository lists,
                          ListMemberRepository listMembers) {
        this.journeys = journeys;
        this.nodes = nodes;
        this.edges = edges;
        this.participants = participants;
        this.sends = sends;
        this.events = events;
        this.lists = lists;
        this.listMembers = listMembers;
    }

    public record Finding(String severity, String code, Long nodeId, String message) {}

    public Journey require(Long id) {
        return journeys.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such journey."));
    }

    // ==================================================================
    // Validation
    // ==================================================================

    /**
     * Everything wrong with a journey, worst first. A BLOCK stops activation; a WARN
     * is something a marketer should look at but is allowed to overrule, because
     * plenty of legitimate designs look odd from outside.
     */
    public List<Finding> validate(Long journeyId) {
        Journey journey = require(journeyId);
        List<JourneyNode> allNodes = nodes.findByJourneyIdOrderByStageAscSortOrderAsc(journeyId);
        List<JourneyEdge> allEdges = edges.findByJourneyIdOrderBySortOrderAsc(journeyId);

        List<Finding> out = new ArrayList<>();
        Map<Long, JourneyNode> byId = new LinkedHashMap<>();
        for (JourneyNode n : allNodes) byId.put(n.getId(), n);

        Map<Long, List<JourneyEdge>> outgoing = new HashMap<>();
        Map<Long, List<JourneyEdge>> incoming = new HashMap<>();
        for (JourneyEdge e : allEdges) {
            outgoing.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>()).add(e);
            incoming.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e);
        }

        // ---------- sources ----------
        List<JourneyNode> sources = allNodes.stream().filter(JourneyNode::isSource).toList();
        if (sources.isEmpty()) {
            out.add(new Finding(BLOCK, "NO_SOURCE", null,
                    "This journey has no base sheet. Add at least one list to start from."));
        }
        for (JourneyNode source : sources) {
            if (source.getSourceListId() == null) {
                out.add(new Finding(BLOCK, "SOURCE_NO_LIST", source.getId(),
                        "\"" + source.getName() + "\" has no list chosen."));
            } else if (!lists.existsById(source.getSourceListId())) {
                out.add(new Finding(BLOCK, "SOURCE_LIST_GONE", source.getId(),
                        "The list behind \"" + source.getName() + "\" has been deleted."));
            } else if (listMembers.countMailable(source.getSourceListId()) == 0) {
                out.add(new Finding(WARN, "SOURCE_EMPTY", source.getId(),
                        "\"" + source.getName() + "\" has nobody mailable on it, so this branch "
                        + "will sit idle."));
            }
            if (outgoing.getOrDefault(source.getId(), List.of()).isEmpty()) {
                out.add(new Finding(BLOCK, "SOURCE_NO_NEXT", source.getId(),
                        "\"" + source.getName() + "\" does not lead anywhere."));
            }
        }

        // ---------- emails ----------
        for (JourneyNode node : allNodes) {
            if (!node.isEmail()) continue;
            if (isBlank(node.getSubject())) {
                out.add(new Finding(BLOCK, "EMAIL_NO_SUBJECT", node.getId(),
                        "\"" + node.getName() + "\" has no subject line."));
            }
            if (isBlank(node.getHtmlBody())) {
                out.add(new Finding(BLOCK, "EMAIL_NO_BODY", node.getId(),
                        "\"" + node.getName() + "\" has an empty body."));
            }
            if (outgoing.getOrDefault(node.getId(), List.of()).isEmpty()) {
                out.add(new Finding(INFO, "EMAIL_IS_LAST", node.getId(),
                        "\"" + node.getName() + "\" is the end of its branch. Anyone reaching it "
                        + "leaves the journey after that message."));
            }
        }

        // ---------- conditions ----------
        for (JourneyNode node : allNodes) {
            if (!node.isCondition()) continue;
            List<JourneyEdge> branches = outgoing.getOrDefault(node.getId(), List.of());

            if (branches.isEmpty()) {
                out.add(new Finding(BLOCK, "CONDITION_NO_BRANCHES", node.getId(),
                        "\"" + node.getName() + "\" has no branches, so nobody reaching it can go anywhere."));
                continue;
            }
            boolean hasElse = branches.stream().anyMatch(e -> e.conditionType() == ConditionType.ELSE);
            if (!hasElse) {
                // Without this, a person matching none of the drawn branches has nowhere
                // defined to go, and "stuck" should be impossible by construction rather
                // than a state to debug at 3am.
                out.add(new Finding(BLOCK, "CONDITION_NO_ELSE", node.getId(),
                        "\"" + node.getName() + "\" needs an \"everyone else\" branch. Without one, "
                        + "anybody whose outcome you did not draw has nowhere to go."));
            }
            Set<String> seen = new HashSet<>();
            for (JourneyEdge branch : branches) {
                if (!seen.add(String.valueOf(branch.getCondition()))) {
                    out.add(new Finding(WARN, "CONDITION_DUPLICATE_BRANCH", node.getId(),
                            "\"" + node.getName() + "\" has two branches for "
                            + branch.conditionType().getLabel() + ". Only the first will ever be taken."));
                }
                if (branch.conditionType().needsArgument() && isBlank(branch.getConditionArg())) {
                    out.add(new Finding(BLOCK, "CONDITION_NO_LINK", node.getId(),
                            "The \"clicked a specific link\" branch of \"" + node.getName()
                            + "\" does not say which link."));
                }
            }

            JourneyNode measured = resolveMeasuredNode(node, byId, incoming);
            if (measured == null) {
                out.add(new Finding(BLOCK, "CONDITION_NOTHING_TO_MEASURE", node.getId(),
                        "\"" + node.getName() + "\" has no email before it, so there is no outcome "
                        + "to judge."));
            } else if (node.getEvaluateAfterMinutes() < 60) {
                out.add(new Finding(WARN, "CONDITION_TOO_SOON", node.getId(),
                        "\"" + node.getName() + "\" judges the result after only "
                        + node.getEvaluateAfterMinutes() + " minutes. Most people who open a message "
                        + "do so within a day, so this will read slow readers as non-openers."));
            }
        }

        // ---------- splits ----------
        for (JourneyNode node : allNodes) {
            if (!node.isSplit()) continue;
            List<JourneyEdge> arms = outgoing.getOrDefault(node.getId(), List.of());
            if (arms.size() < 2) {
                out.add(new Finding(BLOCK, "SPLIT_TOO_FEW_ARMS", node.getId(),
                        "\"" + node.getName() + "\" needs at least two versions to compare."));
                continue;
            }
            double total = arms.stream().mapToDouble(JourneyEdge::getWeight).sum();
            if (total <= 0) {
                out.add(new Finding(BLOCK, "SPLIT_ZERO_WEIGHTS", node.getId(),
                        "Every version under \"" + node.getName() + "\" has a weight of zero, so "
                        + "nobody would receive anything."));
            }
            for (JourneyEdge arm : arms) {
                if (arm.getWeight() == 0) {
                    out.add(new Finding(INFO, "SPLIT_ARM_PAUSED", node.getId(),
                            "Version " + arm.getArmCode() + " of \"" + node.getName()
                            + "\" is set to 0% and will receive nobody."));
                }
            }
        }

        // ---------- loops ----------
        for (JourneyEdge edge : allEdges) {
            if (!edge.isLoopBack()) continue;
            boolean hasEscape = outgoing.getOrDefault(edge.getFromNodeId(), List.of()).stream()
                    .anyMatch(JourneyEdge::isExhausted)
                    || allEdges.stream().anyMatch(e ->
                            Objects.equals(e.getFromNodeId(), edge.getFromNodeId()) && e.isExhausted());
            JourneyNode from = byId.get(edge.getFromNodeId());
            String label = from == null ? "a node" : "\"" + from.getName() + "\"";
            if (!hasEscape) {
                out.add(new Finding(WARN, "LOOP_NO_ESCAPE", edge.getFromNodeId(),
                        "The loop at " + label + " has no branch for people who never respond. "
                        + "After " + journey.getMaxLoopIterations() + " passes they will simply leave "
                        + "the journey, which is safe but silent."));
            }
            int cycleDelay = cycleDelayMinutes(edge, byId, outgoing);
            if (cycleDelay > 0 && cycleDelay < journey.getMinGapHours() * 60) {
                out.add(new Finding(BLOCK, "LOOP_TOO_TIGHT", edge.getFromNodeId(),
                        "One pass round the loop at " + label + " takes " + (cycleDelay / 60)
                        + " hours, under the " + journey.getMinGapHours() + " hour minimum gap "
                        + "between messages. Lengthen the wait or lower the gap."));
            }
            if (!cycleHasEmail(edge, byId, outgoing)) {
                out.add(new Finding(BLOCK, "LOOP_NO_EMAIL", edge.getFromNodeId(),
                        "The loop at " + label + " sends nothing, so it would spin without ever "
                        + "changing anyone's situation."));
            }
        }

        // ---------- reachability ----------
        Set<Long> reachable = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (JourneyNode source : sources) { reachable.add(source.getId()); queue.add(source.getId()); }
        while (!queue.isEmpty()) {
            for (JourneyEdge e : outgoing.getOrDefault(queue.poll(), List.of())) {
                if (reachable.add(e.getToNodeId())) queue.add(e.getToNodeId());
            }
        }
        for (JourneyNode node : allNodes) {
            if (node.isSource() || reachable.contains(node.getId())) continue;
            out.add(new Finding(WARN, "UNREACHABLE", node.getId(),
                    "Nothing leads to \"" + node.getName() + "\", so it will never run."));
        }

        // ---------- worst case volume ----------
        long audience = 0;
        for (JourneyNode source : sources) {
            if (source.getSourceListId() != null && lists.existsById(source.getSourceListId())) {
                audience += listMembers.countMailable(source.getSourceListId());
            }
        }
        long worstCase = audience * journey.getMaxEmailsPerParticipant();
        if (worstCase > 0) {
            out.add(new Finding(INFO, "VOLUME", null,
                    audience + " people are eligible. With the cap of "
                    + journey.getMaxEmailsPerParticipant() + " emails each, this journey can send at "
                    + "most " + worstCase + " messages in total."));
        }

        out.sort(Comparator.comparingInt(f -> switch (f.severity()) {
            case BLOCK -> 0; case WARN -> 1; default -> 2; }));
        return out;
    }

    public static boolean blocked(List<Finding> findings) {
        return findings.stream().anyMatch(f -> BLOCK.equals(f.severity()));
    }

    /**
     * The nearest email above a condition, which is the message it judges. Walking
     * backwards rather than asking the marketer means the common case needs no
     * configuration, and pinning the answer at activation means a later edit cannot
     * silently repoint a running condition at a different message.
     */
    private JourneyNode resolveMeasuredNode(JourneyNode condition, Map<Long, JourneyNode> byId,
                                            Map<Long, List<JourneyEdge>> incoming) {
        if (condition.getMeasuresNodeId() != null) {
            JourneyNode pinned = byId.get(condition.getMeasuresNodeId());
            if (pinned != null) return pinned;
        }
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(condition.getId());
        seen.add(condition.getId());
        while (!queue.isEmpty()) {
            for (JourneyEdge e : incoming.getOrDefault(queue.poll(), List.of())) {
                if (e.isLoopBack()) continue; // walking a loop backwards never terminates usefully
                JourneyNode parent = byId.get(e.getFromNodeId());
                if (parent == null || !seen.add(parent.getId())) continue;
                if (parent.isEmail()) return parent;
                queue.add(parent.getId());
            }
        }
        return null;
    }

    /**
     * The nodes genuinely inside a loop: those reachable from where the loop lands
     * that can also get back to where it started.
     *
     * Following the first outgoing edge is not good enough. A loop almost always runs
     * back through a condition, and a condition's first branch is usually the goal
     * exit, so a naive walk leaves the cycle immediately and concludes the loop sends
     * nothing. That produced a blocking finding on a perfectly good journey.
     */
    private Set<Long> cycleNodes(JourneyEdge loop, Map<Long, List<JourneyEdge>> outgoing) {
        Set<Long> forward = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(loop.getToNodeId());
        forward.add(loop.getToNodeId());
        while (!queue.isEmpty()) {
            for (JourneyEdge e : outgoing.getOrDefault(queue.poll(), List.of())) {
                if (forward.add(e.getToNodeId())) queue.add(e.getToNodeId());
            }
        }

        // Anything that can reach the far end of the loop edge.
        Set<Long> backward = new LinkedHashSet<>();
        backward.add(loop.getFromNodeId());
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<Long, List<JourneyEdge>> entry : outgoing.entrySet()) {
                if (backward.contains(entry.getKey())) continue;
                for (JourneyEdge e : entry.getValue()) {
                    if (backward.contains(e.getToNodeId())) {
                        backward.add(entry.getKey());
                        grew = true;
                        break;
                    }
                }
            }
        }

        forward.retainAll(backward);
        forward.add(loop.getFromNodeId());
        return forward;
    }

    /**
     * The shortest time a person could get round the loop, which is the number the
     * minimum-gap rule has to be judged against. Taking the shortest path rather than
     * an average is deliberate: the guard exists to stop anybody being mailed twice
     * in quick succession, and it is the fastest route that decides that.
     */
    private int cycleDelayMinutes(JourneyEdge loop, Map<Long, JourneyNode> byId,
                                  Map<Long, List<JourneyEdge>> outgoing) {
        Set<Long> cycle = cycleNodes(loop, outgoing);
        Map<Long, Integer> best = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();

        best.put(loop.getToNodeId(), costOf(byId.get(loop.getToNodeId())));
        queue.add(loop.getToNodeId());

        while (!queue.isEmpty()) {
            Long at = queue.poll();
            int here = best.getOrDefault(at, 0);
            if (Objects.equals(at, loop.getFromNodeId())) continue;
            for (JourneyEdge e : outgoing.getOrDefault(at, List.of())) {
                if (!cycle.contains(e.getToNodeId())) continue;
                int next = here + costOf(byId.get(e.getToNodeId()));
                if (next < best.getOrDefault(e.getToNodeId(), Integer.MAX_VALUE)) {
                    best.put(e.getToNodeId(), next);
                    queue.add(e.getToNodeId());
                }
            }
        }
        return best.getOrDefault(loop.getFromNodeId(), 0);
    }

    private static int costOf(JourneyNode node) {
        if (node == null) return 0;
        return node.getDelayMinutes() + (node.isCondition() ? node.getEvaluateAfterMinutes() : 0);
    }

    private boolean cycleHasEmail(JourneyEdge loop, Map<Long, JourneyNode> byId,
                                  Map<Long, List<JourneyEdge>> outgoing) {
        for (Long id : cycleNodes(loop, outgoing)) {
            JourneyNode node = byId.get(id);
            if (node != null && node.isEmail()) return true;
        }
        return false;
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    /**
     * Turns the flowchart on. Conditions are pinned to the message they measure at
     * this moment, so that answer cannot drift under a journey that is already
     * running.
     */
    @Transactional
    public List<Finding> activate(Long journeyId) {
        Journey journey = require(journeyId);
        List<Finding> findings = validate(journeyId);
        if (blocked(findings)) return findings;

        if (journey.getStartAt() != null
                && journey.getStartAt().isBefore(LocalDateTime.now().minusHours(1))) {
            findings.add(new Finding(BLOCK, "START_IN_PAST", null,
                    "The start time is " + journey.getStartAt() + ", which is more than an hour ago. "
                    + "Clear it to start now, or set a future time. Backfilling days of schedule "
                    + "at once would send everything at once."));
            return findings;
        }

        List<JourneyNode> allNodes = nodes.findByJourneyIdOrderByStageAscSortOrderAsc(journeyId);
        List<JourneyEdge> allEdges = edges.findByJourneyIdOrderBySortOrderAsc(journeyId);
        Map<Long, JourneyNode> byId = new LinkedHashMap<>();
        for (JourneyNode n : allNodes) byId.put(n.getId(), n);
        Map<Long, List<JourneyEdge>> incoming = new HashMap<>();
        for (JourneyEdge e : allEdges) incoming.computeIfAbsent(e.getToNodeId(), k -> new ArrayList<>()).add(e);

        for (JourneyNode node : allNodes) {
            if (!node.isCondition() || node.getMeasuresNodeId() != null) continue;
            JourneyNode measured = resolveMeasuredNode(node, byId, incoming);
            if (measured != null) {
                node.setMeasuresNodeId(measured.getId());
                nodes.save(node);
            }
        }

        journey.setStatus(Journey.ACTIVE);
        journey.setActivatedAt(LocalDateTime.now());
        journey.setPauseReason(null);
        journeys.save(journey);
        events.save(new JourneyEvent(journeyId, null, null, null, JourneyEvent.ENTERED,
                "Journey activated by " + AuditService.currentActor() + "."));
        return findings;
    }

    @Transactional
    public void pause(Long journeyId, String reason) {
        Journey journey = require(journeyId);
        journey.setStatus(Journey.PAUSED);
        journey.setPauseReason(reason);
        journeys.save(journey);
        events.save(new JourneyEvent(journeyId, null, null, null, JourneyEvent.DEFERRED,
                "Paused: " + reason));
    }

    @Transactional
    public void resume(Long journeyId) {
        Journey journey = require(journeyId);
        if (!Journey.PAUSED.equals(journey.getStatus()))
            throw new IllegalStateException("That journey is not paused.");
        journey.setStatus(Journey.ACTIVE);
        journey.setPauseReason(null);
        journeys.save(journey);
        events.save(new JourneyEvent(journeyId, null, null, null, JourneyEvent.ENTERED,
                "Resumed by " + AuditService.currentActor() + "."));
    }

    /**
     * Stops everything and lets everyone out, keeping whatever sheet they were on.
     * The campaigns and their recipients survive on purpose: aborting a journey must
     * not delete the record of what was already sent.
     */
    @Transactional
    public int abort(Long journeyId) {
        Journey journey = require(journeyId);
        LocalDateTime now = LocalDateTime.now();
        int exited = 0;
        for (JourneyParticipant p : participants.findByJourneyIdOrderByEnteredAtAsc(
                journeyId, org.springframework.data.domain.PageRequest.of(0, 20_000)).getContent()) {
            if (!p.isActive()) continue;
            p.exit("ABORTED", now);
            participants.save(p);
            exited++;
        }
        journey.setStatus(Journey.ABORTED);
        journey.setCompletedAt(now);
        journeys.save(journey);
        events.save(new JourneyEvent(journeyId, null, null, null, JourneyEvent.EXITED,
                "Aborted by " + AuditService.currentActor() + ". " + exited + " people were let out."));
        return exited;
    }

    // ==================================================================
    // Copy a branch
    // ==================================================================

    /**
     * Deep-copies a subtree onto another base sheet, which is the "build it once for
     * Indian doctors, then copy it to International doctors and edit" step.
     *
     * Everything about the copy is new: new node keys, new ids, zeroed counts. An
     * edge is only carried over when both of its ends are inside the subtree, so a
     * loop-back that stays within the copied branch survives and an edge pointing out
     * of it does not silently rewire the original.
     */
    @Transactional
    public Map<String, Object> copyBranch(Long journeyId, Long rootNodeId, Long attachToNodeId,
                                          boolean move) {
        Journey journey = require(journeyId);
        if (!journey.isStructurallyEditable())
            throw new IllegalStateException("Pause the journey before changing its shape.");

        List<JourneyNode> allNodes = nodes.findByJourneyIdOrderByStageAscSortOrderAsc(journeyId);
        List<JourneyEdge> allEdges = edges.findByJourneyIdOrderBySortOrderAsc(journeyId);
        Map<Long, JourneyNode> byId = new LinkedHashMap<>();
        for (JourneyNode n : allNodes) byId.put(n.getId(), n);
        Map<Long, List<JourneyEdge>> outgoing = new HashMap<>();
        for (JourneyEdge e : allEdges) outgoing.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>()).add(e);

        if (!byId.containsKey(rootNodeId)) throw new IllegalArgumentException("No such node.");
        if (!byId.containsKey(attachToNodeId)) throw new IllegalArgumentException("No such target node.");

        // Collect the subtree.
        Set<Long> subtree = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootNodeId);
        subtree.add(rootNodeId);
        while (!queue.isEmpty()) {
            for (JourneyEdge e : outgoing.getOrDefault(queue.poll(), List.of())) {
                if (subtree.add(e.getToNodeId())) queue.add(e.getToNodeId());
            }
        }
        if (subtree.contains(attachToNodeId))
            throw new IllegalArgumentException("That would attach the branch inside itself.");

        if (move) {
            // A move is a rewire, not a copy: drop the edges that led into the root
            // and draw one new one from the target.
            for (JourneyEdge e : allEdges) {
                if (Objects.equals(e.getToNodeId(), rootNodeId)) edges.delete(e);
            }
            JourneyEdge link = new JourneyEdge(journeyId, attachToNodeId, rootNodeId);
            edges.save(link);
            bumpVersion(journey);
            return Map.of("moved", subtree.size(), "rootNodeId", rootNodeId);
        }

        String suffix = "-c" + (System.currentTimeMillis() % 100000);
        Map<Long, Long> remap = new LinkedHashMap<>();
        int xOffset = 0, yOffset = 0;
        JourneyNode target = byId.get(attachToNodeId);
        JourneyNode root = byId.get(rootNodeId);
        if (target != null && root != null) {
            xOffset = target.getX() - root.getX();
            yOffset = target.getY() + 140 - root.getY();
        }

        for (Long id : subtree) {
            JourneyNode original = byId.get(id);
            JourneyNode copy = new JourneyNode(journeyId, original.getNodeKey() + suffix,
                    original.getType(), original.getName());
            copy.setX(original.getX() + xOffset);
            copy.setY(original.getY() + yOffset);
            copy.setStage(original.getStage());
            copy.setSortOrder(original.getSortOrder());
            copy.setSubject(original.getSubject());
            copy.setPreheader(original.getPreheader());
            copy.setFromName(original.getFromName());
            copy.setReplyTo(original.getReplyTo());
            copy.setHtmlBody(original.getHtmlBody());
            copy.setTrackOpens(original.isTrackOpens());
            copy.setTrackClicks(original.isTrackClicks());
            copy.setDelayMinutes(original.getDelayMinutes());
            copy.setAbsoluteAt(original.getAbsoluteAt());
            copy.setEvaluateAfterMinutes(original.getEvaluateAfterMinutes());
            copy.setExitBucket(original.getExitBucket());
            // measuresNodeId is deliberately not carried over. It is re-resolved at
            // activation against the copy's own ancestors, so the copied condition
            // judges the copied email rather than the original one.
            copy.setSourceListId(original.isSource() ? null : original.getSourceListId());
            nodes.save(copy);
            remap.put(id, copy.getId());
        }

        int copiedEdges = 0;
        for (JourneyEdge e : allEdges) {
            if (!subtree.contains(e.getFromNodeId()) || !subtree.contains(e.getToNodeId())) continue;
            JourneyEdge copy = new JourneyEdge(journeyId,
                    remap.get(e.getFromNodeId()), remap.get(e.getToNodeId()));
            copy.setCondition(e.getCondition());
            copy.setConditionArg(e.getConditionArg());
            copy.setWeight(e.getWeight());
            copy.setArmCode(e.getArmCode());
            copy.setLoopBack(e.isLoopBack());
            copy.setExhausted(e.isExhausted());
            copy.setSortOrder(e.getSortOrder());
            edges.save(copy);
            copiedEdges++;
        }

        JourneyEdge link = new JourneyEdge(journeyId, attachToNodeId, remap.get(rootNodeId));
        edges.save(link);
        bumpVersion(journey);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("copiedNodes", subtree.size());
        out.put("copiedEdges", copiedEdges);
        out.put("newRootNodeId", remap.get(rootNodeId));
        return out;
    }

    public void bumpVersion(Journey journey) {
        journey.setDefinitionVersion(journey.getDefinitionVersion() + 1);
        journeys.save(journey);
    }

    // ==================================================================
    // Conditional sheets
    // ==================================================================

    /**
     * The sheets, with counts. These are read straight off the participant rows, so
     * they cannot drift out of step with the flow the way a separately maintained
     * set of lists would.
     */
    public List<Map<String, Object>> sheets(Long journeyId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : participants.bucketCounts(journeyId)) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> out = new ArrayList<>();
        for (JourneyBucket bucket : JourneyBucket.values()) {
            long count = counts.getOrDefault(bucket.name(), 0L);
            if (bucket == JourneyBucket.NONE && count == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", bucket.name());
            row.put("label", bucket.getLabel());
            row.put("description", bucket.getDescription());
            row.put("terminal", bucket.mustStop());
            row.put("goal", bucket.isGoal());
            row.put("count", count);
            row.put("share", total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0);
            out.add(row);
        }
        return out;
    }

    /**
     * Turns a sheet into a reusable mailing list, which is what makes the sheets
     * operational rather than decorative: "everyone who never opened" becomes an
     * audience you can mail from the ordinary composer.
     */
    @Transactional
    public Map<String, Object> saveSheetAsList(Long journeyId, String bucketName, String listName) {
        Journey journey = require(journeyId);
        JourneyBucket bucket = JourneyBucket.parse(bucketName);
        String name = listName == null || listName.isBlank()
                ? journey.getName() + " - " + bucket.getLabel() : listName.trim();

        if (lists.existsByName(name))
            throw new IllegalArgumentException("A list called \"" + name + "\" already exists.");

        MailingList list = lists.save(new MailingList(name,
                "People filed under " + bucket.getLabel() + " in the journey \"" + journey.getName() + "\"",
                "IMPORT", AuditService.currentActor()));

        int added = 0, skipped = 0;
        for (JourneyParticipant p : participants.findByJourneyIdAndBucket(journeyId, bucket.name())) {
            // A sheet of bounced addresses is exactly the list you must not hand
            // someone to mail again, so suppressed people are counted out loud
            // rather than quietly included.
            if (bucket.mustStop() && bucket != JourneyBucket.UNSUBSCRIBED) { skipped++; continue; }
            if (listMembers.existsByListIdAndSubscriberId(list.getId(), p.getSubscriberId())) continue;
            listMembers.save(new ListMember(list.getId(), p.getSubscriberId()));
            added++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", list.getId());
        out.put("name", name);
        out.put("added", added);
        out.put("skipped", skipped);
        out.put("message", "Saved " + added + " people as \"" + name + "\"."
                + (skipped > 0 ? " " + skipped + " were left out because their address is suppressed." : ""));
        return out;
    }

    // ==================================================================
    // Reporting
    // ==================================================================

    /** Headline numbers plus how many people are standing on each node. */
    public Map<String, Object> stats(Long journeyId) {
        Journey journey = require(journeyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", journey.getId());
        out.put("name", journey.getName());
        out.put("status", journey.getStatus());
        out.put("pauseReason", journey.getPauseReason());
        out.put("definitionVersion", journey.getDefinitionVersion());
        out.put("participants", participants.countByJourneyId(journeyId));
        out.put("active", participants.countByJourneyIdAndState(journeyId, JourneyParticipant.ACTIVE));
        out.put("exited", participants.countByJourneyIdAndState(journeyId, JourneyParticipant.EXITED));
        out.put("failed", participants.countByJourneyIdAndState(journeyId, JourneyParticipant.FAILED));

        Map<String, Long> occupancy = new LinkedHashMap<>();
        for (Object[] row : participants.nodeOccupancy(journeyId)) {
            occupancy.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        out.put("nodeOccupancy", occupancy);
        out.put("sheets", sheets(journeyId));
        return out;
    }

    /**
     * A/B comparison for one split, from the sheets rather than from a second set of
     * counters. Honest about small numbers: at a few hundred doctors most differences
     * are noise, and a tool that names a winner on six opens teaches people to trust
     * a number that has not earned it.
     */
    public Map<String, Object> variantReport(Long journeyId, Long splitNodeId) {
        Map<String, Map<String, Long>> byArm = new LinkedHashMap<>();
        for (Object[] row : participants.variantBreakdown(journeyId, splitNodeId)) {
            String arm = row[0] == null ? "?" : String.valueOf(row[0]);
            String bucket = String.valueOf(row[1]);
            byArm.computeIfAbsent(arm, k -> new LinkedHashMap<>())
                 .merge(bucket, ((Number) row[2]).longValue(), Long::sum);
        }

        List<Map<String, Object>> arms = new ArrayList<>();
        long smallestArm = Long.MAX_VALUE;
        for (Map.Entry<String, Map<String, Long>> entry : byArm.entrySet()) {
            Map<String, Long> counts = entry.getValue();
            long assigned = counts.values().stream().mapToLong(Long::longValue).sum();
            long clicked = counts.getOrDefault(JourneyBucket.CLICKED.name(), 0L);
            long opened = clicked + counts.getOrDefault(JourneyBucket.OPENED_NOT_CLICKED.name(), 0L);
            long bounced = counts.getOrDefault(JourneyBucket.BOUNCED.name(), 0L);
            long unsubscribed = counts.getOrDefault(JourneyBucket.UNSUBSCRIBED.name(), 0L);
            long delivered = Math.max(0, assigned - bounced
                    - counts.getOrDefault(JourneyBucket.NOT_DELIVERED.name(), 0L));

            Map<String, Object> arm = new LinkedHashMap<>();
            arm.put("arm", entry.getKey());
            arm.put("assigned", assigned);
            arm.put("delivered", delivered);
            arm.put("opened", opened);
            arm.put("clicked", clicked);
            arm.put("bounced", bounced);
            arm.put("unsubscribed", unsubscribed);
            arm.put("openRate", rateOrNull(opened, delivered));
            arm.put("clickRate", rateOrNull(clicked, delivered));
            arm.put("clickToOpenRate", rateOrNull(clicked, opened));
            arms.add(arm);
            smallestArm = Math.min(smallestArm, delivered);
        }
        if (smallestArm == Long.MAX_VALUE) smallestArm = 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arms", arms);
        out.put("smallestArm", smallestArm);
        out.put("callable", smallestArm >= 300);
        // The detectable difference at this sample size, from the usual two-proportion
        // power calculation at 95% confidence and 80% power around a 25% baseline. It
        // is the number that tells a marketer their test cannot see what they are
        // looking for, which is more useful than any p-value.
        out.put("detectableDifferencePoints", smallestArm == 0 ? null
                : Math.round(100 * 2.8 * Math.sqrt(2 * 0.25 * 0.75 / smallestArm) * 10) / 10.0);
        out.put("verdict", smallestArm >= 300
                ? "Enough data to compare these versions."
                : "Not enough data to call this. With " + smallestArm + " delivered per version, "
                  + "only a very large difference would be distinguishable from chance. "
                  + "Read the numbers, do not crown a winner.");
        return out;
    }

    private static Double rateOrNull(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }

    // ==================================================================

    @Transactional
    public void deleteJourney(Long journeyId) {
        Journey journey = require(journeyId);
        if (journey.isRunning())
            throw new IllegalStateException("Stop the journey before deleting it.");
        events.deleteByJourneyId(journeyId);
        participants.deleteByJourneyId(journeyId);
        sends.deleteByJourneyId(journeyId);
        edges.deleteByJourneyId(journeyId);
        nodes.deleteByJourneyId(journeyId);
        journeys.deleteById(journeyId);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
