package com.jarurat.mailer.journey;

import com.jarurat.mailer.analytics.OpenClassification;
import com.jarurat.mailer.analytics.TrackingEvent;
import com.jarurat.mailer.analytics.TrackingEventRepository;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.models.CampaignRecipient;
import com.jarurat.mailer.models.GlobalSuppression;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.*;
import com.jarurat.mailer.services.SesSender;
import com.jarurat.mailer.services.SuppressionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Runs the flowcharts.
 *
 * The whole engine rests on one decision: a journey email is an ordinary Campaign.
 * Each (node, loop iteration) materialises a real campaign row, participants are
 * inserted as real campaign_recipient rows, and the send goes through the same
 * SesSender path a single-email blast uses. That means open tracking, click
 * rewriting, the Apple MPP classifier, the suppression re-check, the unsubscribe
 * footer, the message log and every analytics query already work on journey mail
 * without a line of new code, and there is no second implementation to disagree
 * with the first.
 *
 * The tick is deliberately small and idempotent. It claims a bounded page of due
 * participants, advances each one, and writes the new position with an optimistic
 * lock. A restart mid-tick loses at most the work in flight; nothing is sent twice,
 * because the send is keyed on a campaign_recipient row whose unique constraint on
 * (campaignId, subscriberId) refuses a duplicate.
 */
@Service
public class JourneyEngine {

    /** Nodes that cost nothing to run are chained inside one pass rather than one per tick. */
    private static final int MAX_HOPS_PER_STEP = 24;

    /** How many people one journey may advance in a single tick. */
    private static final int TICK_PAGE = 200;

    /** How many people one journey admits from one base sheet per tick. */
    private static final int ADMIT_PER_TICK = 1000;

    private final JourneyRepository journeys;
    private final JourneyNodeRepository nodes;
    private final JourneyEdgeRepository edges;
    private final JourneyParticipantRepository participants;
    private final JourneySendRepository sends;
    private final JourneyEventRepository events;

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final SubscriberRepository subscribers;
    private final ListMemberRepository listMembers;
    private final MailingListRepository lists;
    private final GlobalSuppressionRepository suppressions;
    private final TrackingEventRepository tracking;
    private final SuppressionService suppression;
    private final SesSender ses;
    private final MessageLogService messageLog;

    /**
     * Salt for the A/B allocator. Read from configuration so it is stable across
     * restarts: if it changed, everyone already assigned would be re-bucketed and
     * the arms would stop being comparable.
     */
    private final String assignmentSalt;

    public JourneyEngine(JourneyRepository journeys,
                         JourneyNodeRepository nodes,
                         JourneyEdgeRepository edges,
                         JourneyParticipantRepository participants,
                         JourneySendRepository sends,
                         JourneyEventRepository events,
                         CampaignRepository campaigns,
                         CampaignRecipientRepository recipients,
                         SubscriberRepository subscribers,
                         ListMemberRepository listMembers,
                         MailingListRepository lists,
                         GlobalSuppressionRepository suppressions,
                         TrackingEventRepository tracking,
                         SuppressionService suppression,
                         SesSender ses,
                         MessageLogService messageLog,
                         @Value("${journey.assignmentSalt:jcf-journey-v1}") String assignmentSalt) {
        this.journeys = journeys;
        this.nodes = nodes;
        this.edges = edges;
        this.participants = participants;
        this.sends = sends;
        this.events = events;
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.subscribers = subscribers;
        this.listMembers = listMembers;
        this.lists = lists;
        this.suppressions = suppressions;
        this.tracking = tracking;
        this.suppression = suppression;
        this.ses = ses;
        this.messageLog = messageLog;
        this.assignmentSalt = assignmentSalt;
    }

    // ==================================================================
    // The tick
    // ==================================================================

    /**
     * Offset from the campaign scheduler's own minute so the two are not competing
     * for the same connections and the same SES slots at the same instant.
     */
    @Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    public void tick() {
        for (Journey journey : journeys.findByStatus(Journey.ACTIVE)) {
            try {
                runOne(journey.getId());
            } catch (Exception e) {
                System.err.println("Journey " + journey.getName() + " tick failed: " + e);
            }
        }
    }

    /**
     * Every scheduling timestamp is truncated to a whole second.
     *
     * Not cosmetic. A row written as 10:00:00.413273800 can come back from the
     * database rounded to 10:00:00.413274, and "due at or before now" then misses it
     * by a microsecond. Postgres truncates and H2 rounds, so the same code silently
     * behaved differently in the test and on the box. Since the tick runs once a
     * minute, sub-second precision was never meaningful anyway; giving it up removes
     * the whole class of problem rather than papering over one instance of it.
     */
    private static LocalDateTime clock() {
        return LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    /** One journey's pass. Public so the console can force a run without waiting a minute. */
    public Map<String, Object> runOne(Long journeyId) {
        Journey journey = journeys.findById(journeyId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (journey == null || !journey.isRunning()) {
            out.put("skipped", "not active");
            return out;
        }

        LocalDateTime now = clock();

        if (journey.getStartAt() != null && journey.getStartAt().isAfter(now)) {
            out.put("skipped", "starts at " + journey.getStartAt());
            return out;
        }

        if (journey.getDeadlineAt() != null && !journey.getDeadlineAt().isAfter(now)) {
            int closed = closeOutAtDeadline(journey, now);
            out.put("deadlineExits", closed);
            out.put("status", journey.getStatus());
            return out;
        }

        Graph graph = loadGraph(journey.getId());

        int admitted = admit(journey, graph, now);
        int advanced = advanceDue(journey, graph, now);

        out.put("admitted", admitted);
        out.put("advanced", advanced);

        // Completion is "nobody is moving and nobody is scheduled to". A journey with
        // an empty branch must still be able to finish, which is why this asks about
        // active participants rather than about whether every node was reached.
        if (admitted == 0 && participants.countActive(journey.getId()) == 0
                && participants.countByJourneyId(journey.getId()) > 0) {
            journey.setStatus(Journey.COMPLETED);
            journey.setCompletedAt(now);
            journeys.save(journey);
            log(journey.getId(), null, null, null, JourneyEvent.EXITED, "Journey completed.");
        }
        out.put("status", journey.getStatus());
        return out;
    }

    // ==================================================================
    // Admission
    // ==================================================================

    /**
     * Pulls people off the base sheets into the journey.
     *
     * Someone on two base sheets is admitted once, by whichever source sorts first.
     * The unique constraint on (journeyId, subscriberId) is the real guarantee; the
     * check below just avoids a wasted insert attempt per row.
     */
    private int admit(Journey journey, Graph graph, LocalDateTime now) {
        int admitted = 0;
        for (JourneyNode source : graph.byType(JourneyNode.SOURCE)) {
            if (source.getSourceListId() == null) continue;
            if (!lists.existsById(source.getSourceListId())) {
                log(journey.getId(), null, source.getId(), null, JourneyEvent.DEFERRED,
                        "Base sheet " + source.getSourceListId() + " no longer exists, so nobody "
                        + "new is being admitted through \"" + source.getName() + "\".");
                continue;
            }

            // Bounded per tick. A 50k base sheet is admitted over several minutes
            // rather than in one transaction that holds a connection for the whole
            // pass, and everyone still starts on the same node with the same clock.
            List<Object[]> pending = participants.findAdmissible(
                    journey.getId(), source.getSourceListId(), ADMIT_PER_TICK);

            for (Object[] row : pending) {
                Long subscriberId = ((Number) row[0]).longValue();
                String email = String.valueOf(row[1]);
                String name = row[2] == null ? null : String.valueOf(row[2]);

                JourneyParticipant p = new JourneyParticipant(journey.getId(), subscriberId,
                        email, name, source.getId(), journey.getDefinitionVersion());
                p.setNextRunAt(now);
                try {
                    participants.save(p);
                } catch (RuntimeException duplicate) {
                    // Lost a race with another pass, or the person came in through the
                    // other base sheet first. The unique constraint is the guarantee;
                    // this is just the cheap path when it fires.
                    continue;
                }
                log(journey.getId(), p.getId(), source.getId(), email, JourneyEvent.ENTERED,
                        "Admitted from base sheet \"" + source.getName() + "\".");
                admitted++;
            }
        }
        return admitted;
    }

    // ==================================================================
    // Advancing
    // ==================================================================

    private int advanceDue(Journey journey, Graph graph, LocalDateTime now) {
        List<JourneyParticipant> due = participants.findDue(journey.getId(), now,
                PageRequest.of(0, TICK_PAGE));
        if (due.isEmpty()) return 0;

        // The circuit breaker. A tick that would mail an implausible fraction of the
        // journey at once is the signature of a validation hole letting everybody
        // advance together, and pausing is always cheaper than apologising.
        long total = Math.max(1, participants.countByJourneyId(journey.getId()));
        int ceiling = Math.max(journey.getMaxSendsPerTick(), (int) Math.min(total, 50));
        if (due.size() > ceiling) {
            pause(journey, "A single pass would have advanced " + due.size() + " people, above the "
                    + ceiling + " limit for this journey. Paused rather than sending. Check the "
                    + "delays on your nodes, then resume.");
            return 0;
        }

        int advanced = 0;
        for (JourneyParticipant participant : due) {
            try {
                if (step(journey, graph, participant, now)) advanced++;
            } catch (Exception e) {
                participant.setFailureCount(participant.getFailureCount() + 1);
                participant.setLastError(e.getMessage());
                // Backing off rather than retrying immediately: a failure that repeats
                // every minute burns the SES budget and fills the log with one problem.
                participant.setNextRunAt(now.plusMinutes(retryDelayMinutes(participant.getFailureCount())));
                if (participant.getFailureCount() >= 5) {
                    participant.setState(JourneyParticipant.FAILED);
                    participant.setNextRunAt(null);
                }
                participants.save(participant);
                log(journey.getId(), participant.getId(), participant.getCurrentNodeId(),
                        participant.getEmail(), JourneyEvent.SEND_FAILED, String.valueOf(e.getMessage()));
            }
        }
        return advanced;
    }

    private static int retryDelayMinutes(int failures) {
        return switch (failures) { case 1 -> 5; case 2 -> 30; case 3 -> 120; default -> 360; };
    }

    /**
     * Moves one person as far as they can go in one pass.
     *
     * Zero-cost nodes chain inside the loop, so a source that leads to a split that
     * leads to an email all happens now rather than over three minutes. Anything that
     * waits, sends or defers ends the pass.
     */
    private boolean step(Journey journey, Graph graph, JourneyParticipant p, LocalDateTime now) {
        boolean didSomething = false;

        for (int hop = 0; hop < MAX_HOPS_PER_STEP; hop++) {
            if (!p.isActive()) break;

            JourneyNode node = graph.node(p.getCurrentNodeId());
            if (node == null) {
                p.exit("NODE_GONE", now);
                participants.save(p);
                log(journey.getId(), p.getId(), null, p.getEmail(), JourneyEvent.EXITED,
                        "The node this person was standing on no longer exists.");
                return true;
            }

            Outcome outcome = switch (node.getType()) {
                case JourneyNode.SOURCE -> advanceAlongOnly(journey, graph, p, node, now);
                case JourneyNode.WAIT -> advanceAlongOnly(journey, graph, p, node, now);
                case JourneyNode.SPLIT -> runSplit(journey, graph, p, node, now);
                case JourneyNode.EMAIL -> runEmail(journey, graph, p, node, now);
                case JourneyNode.CONDITION -> runCondition(journey, graph, p, node, now);
                case JourneyNode.EXIT -> runExit(journey, p, node, now);
                default -> Outcome.stop("Unknown node type " + node.getType());
            };

            didSomething |= outcome.changed();
            if (!outcome.chain()) break;
        }

        participants.save(p);
        return didSomething;
    }

    // ------------------------------------------------------------------
    // Node handlers
    // ------------------------------------------------------------------

    /** SOURCE and WAIT both mean "go to the one child", differing only in the delay. */
    private Outcome advanceAlongOnly(Journey journey, Graph graph, JourneyParticipant p,
                                     JourneyNode node, LocalDateTime now) {
        List<JourneyEdge> out = graph.outgoing(node.getId());
        if (out.isEmpty()) {
            p.exit("NO_NEXT_STEP", now);
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.EXITED,
                    "\"" + node.getName() + "\" has no outgoing step.");
            return Outcome.stopChanged();
        }
        return moveTo(journey, graph, p, out.get(0), now);
    }

    /**
     * A/B. The arm is a pure function of the person and the node, so a retry, a
     * restart or a second process all reach the same answer, and a person can never
     * receive two arms of the same stage.
     */
    private Outcome runSplit(Journey journey, Graph graph, JourneyParticipant p,
                             JourneyNode node, LocalDateTime now) {
        List<JourneyEdge> arms = graph.outgoing(node.getId());
        if (arms.isEmpty()) {
            p.exit("SPLIT_HAS_NO_ARMS", now);
            return Outcome.stopChanged();
        }

        // Weights are normalised rather than validated to 100. A marketer typing
        // 40/40/30 should see an effective split, not a dialog asking them to do
        // arithmetic. A zero-weight arm is legal and simply receives nobody.
        double total = 0;
        for (JourneyEdge arm : arms) total += arm.getWeight();
        if (total <= 0) {
            p.exit("SPLIT_ALL_WEIGHTS_ZERO", now);
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.EXITED,
                    "Every arm of \"" + node.getName() + "\" has a weight of zero.");
            return Outcome.stopChanged();
        }

        int bucket = stableBucket(node.getNodeKey(), p.getSubscriberId());
        double cursor = 0;
        JourneyEdge chosen = arms.get(arms.size() - 1);
        for (JourneyEdge arm : arms) {
            cursor += arm.getWeight() / total * 10_000d;
            if (bucket < cursor) { chosen = arm; break; }
        }

        p.setVariantArm(chosen.getArmCode() == null ? "A" : chosen.getArmCode());
        p.setVariantNodeId(node.getId());
        log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.ADVANCED,
                "Assigned to arm " + p.getVariantArm() + " of \"" + node.getName() + "\".");
        return moveTo(journey, graph, p, chosen, now);
    }

    /**
     * Sends this node's message to this person, then moves them on.
     *
     * The caps are checked here rather than at the loop edge because this is the only
     * place a message actually leaves, and a cap that is not checked at the point of
     * sending is not a cap.
     */
    private Outcome runEmail(Journey journey, Graph graph, JourneyParticipant p,
                             JourneyNode node, LocalDateTime now) {
        if (isBlank(node.getSubject()) || isBlank(node.getHtmlBody())) {
            // Never send a blank message. Defer instead of failing, so fixing the
            // node in the console rescues everyone waiting on it.
            p.setNextRunAt(now.plusMinutes(30));
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.DEFERRED,
                    "\"" + node.getName() + "\" has no subject or no body, so nothing was sent.");
            return Outcome.stop();
        }

        if (p.getEmailsSent() >= journey.getMaxEmailsPerParticipant()) {
            p.exit("EMAIL_CAP", now);
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.CAPPED,
                    "Reached the cap of " + journey.getMaxEmailsPerParticipant()
                    + " emails from this journey.");
            return Outcome.stopChanged();
        }

        // Frequency guard, across this journey and any other running one. Deferring
        // rather than skipping matters: the person should still get the message, just
        // not today.
        LocalDateTime earliest = earliestAllowedSend(journey, p, now);
        if (earliest.isAfter(now)) {
            p.setNextRunAt(earliest);
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.DEFERRED,
                    "Held until " + earliest + " to respect the " + journey.getMinGapHours()
                    + " hour gap between messages.");
            return Outcome.stop();
        }

        // Late suppression check. Someone can unsubscribe between being admitted and
        // being reached, and the sheets have to reflect that before anything goes out.
        if (suppression.isSuppressed(p.getEmail())) {
            applyObservedSuppression(journey, p, now);
            return Outcome.stopChanged();
        }

        Campaign campaign = campaignFor(journey, node, p.getIterationNo());
        CampaignRecipient recipient = enrol(campaign, p);
        if (recipient == null) {
            // Already enrolled and already handled on an earlier pass. Move on rather
            // than sending twice.
            return advanceAfterEmail(journey, graph, p, node, now, null);
        }

        sendOne(journey, campaign, recipient, p, now);
        p.setEmailsSent(p.getEmailsSent() + 1);
        p.setLastSendAt(now);
        p.setMeasuredRecipientId(recipient.getId());
        p.setFailureCount(0);

        log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.SENT,
                "\"" + node.getName() + "\"" + (p.getIterationNo() > 0
                        ? " (pass " + (p.getIterationNo() + 1) + ")" : "")
                + (p.getVariantArm() == null ? "" : ", arm " + p.getVariantArm()));

        return advanceAfterEmail(journey, graph, p, node, now, recipient);
    }

    private Outcome advanceAfterEmail(Journey journey, Graph graph, JourneyParticipant p,
                                      JourneyNode node, LocalDateTime now, CampaignRecipient sent) {
        List<JourneyEdge> out = graph.outgoing(node.getId());
        if (out.isEmpty()) {
            p.exit("SEQUENCE_END", now);
            return Outcome.stopChanged();
        }
        return moveTo(journey, graph, p, out.get(0), now);
    }

    /**
     * Judges what happened to the measured message and routes accordingly.
     *
     * Two things happen here and they are deliberately separate. The observed outcome
     * updates the person's sheet, which is a statement about what they did. The branch
     * taken is a statement about what the flowchart wants to do next. They usually
     * agree, but a journey that routes both "opened" and "did not open" into the same
     * follow-up is legitimate, and the sheets must still record the difference.
     */
    private Outcome runCondition(Journey journey, Graph graph, JourneyParticipant p,
                                 JourneyNode node, LocalDateTime now) {
        CampaignRecipient measured = p.getMeasuredRecipientId() == null ? null
                : recipients.findById(p.getMeasuredRecipientId()).orElse(null);

        if (measured == null) {
            // Nothing to judge. Treat as undelivered rather than guessing, and route
            // through the normal branches so the flowchart still decides.
            return route(journey, graph, p, node, JourneyBucket.NOT_DELIVERED, now);
        }

        // The clock is per person: 48 hours after the message reached THIS person,
        // not 48 hours after the campaign started.
        LocalDateTime sentAt = measured.getSentAt() == null ? p.getLastSendAt() : measured.getSentAt();
        if (sentAt != null) {
            LocalDateTime ready = sentAt.plusMinutes(node.getEvaluateAfterMinutes())
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            if (ready.isAfter(now)) {
                p.setNextRunAt(ready);
                return Outcome.stop();
            }
        }

        JourneyBucket observed = observe(p, measured);
        return route(journey, graph, p, node, observed, now);
    }

    private Outcome runExit(Journey journey, JourneyParticipant p, JourneyNode node, LocalDateTime now) {
        JourneyBucket bucket = JourneyBucket.parse(node.getExitBucket());
        if (bucket != JourneyBucket.NONE) p.promoteBucket(bucket, now);
        p.exit("EXIT_" + node.getNodeKey(), now);
        log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.EXITED,
                "Left through \"" + node.getName() + "\", filed under " + p.getBucket().getLabel() + ".");
        return Outcome.stopChanged();
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    /** Files the person under what they actually did, then picks the matching branch. */
    private Outcome route(Journey journey, Graph graph, JourneyParticipant p, JourneyNode node,
                          JourneyBucket observed, LocalDateTime now) {
        if (p.promoteBucket(observed, now)) {
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.BUCKETED,
                    "Filed under " + observed.getLabel() + ".");
        }
        if (!p.isActive()) return Outcome.stopChanged(); // an unsubscribe or bounce ended it

        List<JourneyEdge> branches = graph.outgoing(node.getId());
        JourneyEdge fallback = null;
        JourneyEdge chosen = null;

        for (JourneyEdge branch : branches) {
            ConditionType type = branch.conditionType();
            if (type == ConditionType.ELSE) { fallback = branch; continue; }
            if (matches(type, branch, p, observed)) { chosen = branch; break; }
        }
        if (chosen == null) chosen = fallback;

        if (chosen == null) {
            p.exit("NO_MATCHING_BRANCH", now);
            log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.EXITED,
                    "Outcome was " + observed.getLabel() + " and \"" + node.getName()
                    + "\" has no branch for it and no catch-all.");
            return Outcome.stopChanged();
        }

        log(journey.getId(), p.getId(), node.getId(), p.getEmail(), JourneyEvent.ADVANCED,
                "Outcome " + observed.getLabel() + ", took the "
                + (chosen.getCondition() == null ? "next" : chosen.conditionType().getLabel()) + " branch.");
        return moveTo(journey, graph, p, chosen, now);
    }

    /** Whether an observed outcome satisfies one branch's condition. */
    private boolean matches(ConditionType type, JourneyEdge branch,
                            JourneyParticipant p, JourneyBucket observed) {
        return switch (type) {
            case OPENED -> observed == JourneyBucket.OPENED_NOT_CLICKED || observed == JourneyBucket.CLICKED;
            case CLICKED -> observed == JourneyBucket.CLICKED;
            case OPENED_NOT_CLICKED -> observed == JourneyBucket.OPENED_NOT_CLICKED;
            case NOT_OPENED -> observed == JourneyBucket.NOT_OPENED;
            case PRIVACY_UNKNOWN -> observed == JourneyBucket.PRIVACY_UNKNOWN;
            case NOT_DELIVERED -> observed == JourneyBucket.NOT_DELIVERED;
            case BOUNCED -> observed == JourneyBucket.BOUNCED;
            case UNSUBSCRIBED -> observed == JourneyBucket.UNSUBSCRIBED;
            case COMPLAINED -> observed == JourneyBucket.COMPLAINED;
            case CLICKED_SPECIFIC -> observed == JourneyBucket.CLICKED
                    && p.getMeasuredRecipientId() != null
                    && branch.getConditionArg() != null
                    && tracking.countClicksOnLink(p.getMeasuredRecipientId(), branch.getConditionArg()) > 0;
            case ELSE -> true;
        };
    }

    /**
     * Follows one edge, handling the loop case.
     *
     * A loop-back edge is the only place iteration changes, and it is the only place
     * the loop cap is enforced. When the cap is reached the participant leaves through
     * the node's EXHAUSTED edge if one was drawn, and exits cleanly if not, so a
     * branch nobody ever opens terminates in a defined state rather than in a
     * cap-check with nowhere to go.
     */
    private Outcome moveTo(Journey journey, Graph graph, JourneyParticipant p,
                           JourneyEdge edge, LocalDateTime now) {
        if (edge.isLoopBack()) {
            if (p.getLoopCount() >= journey.getMaxLoopIterations()) {
                JourneyEdge escape = graph.exhaustedEdge(edge.getFromNodeId());
                log(journey.getId(), p.getId(), edge.getFromNodeId(), p.getEmail(), JourneyEvent.CAPPED,
                        "Loop ran its " + journey.getMaxLoopIterations() + " passes without a change.");
                if (escape == null) {
                    p.exit("LOOP_EXHAUSTED", now);
                    return Outcome.stopChanged();
                }
                return moveTo(journey, graph, p, escape, now);
            }
            p.setLoopCount(p.getLoopCount() + 1);
            p.setIterationNo(p.getIterationNo() + 1);
            log(journey.getId(), p.getId(), edge.getFromNodeId(), p.getEmail(), JourneyEvent.LOOPED,
                    "Looping back for pass " + (p.getIterationNo() + 1) + ".");
        }

        JourneyNode target = graph.node(edge.getToNodeId());
        if (target == null) {
            p.exit("BROKEN_EDGE", now);
            return Outcome.stopChanged();
        }

        p.setCurrentNodeId(target.getId());
        LocalDateTime due = dueTimeFor(journey, target, now);
        p.setNextRunAt(due);

        // Chain onward in this same pass only when the next node is due right now.
        // Anything with a delay ends the pass and waits for a later tick.
        return due.isAfter(now) ? Outcome.stopChanged() : Outcome.chainChanged();
    }

    /** When a node should run, given how the marketer expressed its timing. */
    private LocalDateTime dueTimeFor(Journey journey, JourneyNode node, LocalDateTime now) {
        LocalDateTime due = now;
        if (node.getAbsoluteAt() != null) {
            due = node.getAbsoluteAt().isAfter(now) ? node.getAbsoluteAt() : now;
        } else if (node.getDelayMinutes() > 0) {
            due = now.plusMinutes(node.getDelayMinutes());
        }
        if (node.isEmail()) due = applyQuietHours(journey, due);
        return due.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    /**
     * Shifts a send out of the quiet window, always forward. Shifting backwards would
     * mean a message arriving earlier than the marketer asked for, which is worse than
     * one arriving a few hours later.
     */
    LocalDateTime applyQuietHours(Journey journey, LocalDateTime when) {
        int start = journey.getQuietStartHour();
        int end = journey.getQuietEndHour();
        if (start == end) return when; // no quiet window configured

        ZoneId zone;
        try { zone = ZoneId.of(journey.getZoneId()); } catch (Exception e) { zone = ZoneId.systemDefault(); }
        int hour = when.atZone(ZoneId.systemDefault()).withZoneSameInstant(zone).getHour();

        boolean overnight = start > end;                       // e.g. 21:00 to 08:00
        boolean quiet = overnight ? (hour >= start || hour < end) : (hour >= start && hour < end);
        if (!quiet) return when;

        LocalDateTime shifted = when.withMinute(0).withSecond(0).withNano(0).withHour(end);
        if (!shifted.isAfter(when)) shifted = shifted.plusDays(1);
        return shifted;
    }

    /** The earliest this person may next be mailed, across every running journey. */
    private LocalDateTime earliestAllowedSend(Journey journey, JourneyParticipant p, LocalDateTime now) {
        LocalDateTime earliest = now;
        int gap = journey.getMinGapHours();
        if (gap <= 0) return earliest;

        for (JourneyParticipant other : participants.findActiveByEmail(p.getEmail())) {
            if (other.getLastSendAt() == null) continue;
            LocalDateTime allowed = other.getLastSendAt().plusHours(gap)
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            if (allowed.isAfter(earliest)) earliest = allowed;
        }
        return earliest;
    }

    // ==================================================================
    // Observation
    // ==================================================================

    /**
     * What actually happened to one message.
     *
     * Checked in rank order, worst first, so a person who opened and then complained
     * is filed as a complaint rather than as a reader. openedAt and lastClickedAt are
     * only ever set by the classifier's HUMAN path, so a scanner sweep and Apple's
     * pre-fetch cannot reach them.
     */
    JourneyBucket observe(JourneyParticipant p, CampaignRecipient r) {
        GlobalSuppression suppressed = suppressions.findById(p.getEmail()).orElse(null);
        if (suppressed != null) {
            String reason = String.valueOf(suppressed.getReason());
            if ("COMPLAINT".equals(reason)) return JourneyBucket.COMPLAINED;
            if ("BOUNCE".equals(reason)) return JourneyBucket.BOUNCED;
            return JourneyBucket.UNSUBSCRIBED;
        }

        if (!"SENT".equals(r.getStatus())) return JourneyBucket.NOT_DELIVERED;

        if (r.getLastClickedAt() != null) return JourneyBucket.CLICKED;
        if (r.getOpenedAt() != null) return JourneyBucket.OPENED_NOT_CLICKED;

        // No human open. Distinguish "nothing at all" from "a machine fetched the
        // pixel on their behalf", because the second is genuinely unknowable and
        // filing it as a non-open would be a claim the data does not support.
        long machineOpens = tracking.countAnyForRecipient(r.getId(), TrackingEvent.OPEN)
                - tracking.countForRecipient(r.getId(), TrackingEvent.OPEN, OpenClassification.HUMAN);
        return machineOpens > 0 ? JourneyBucket.PRIVACY_UNKNOWN : JourneyBucket.NOT_OPENED;
    }

    /** Someone opted out or bounced between admission and being reached. */
    private void applyObservedSuppression(Journey journey, JourneyParticipant p, LocalDateTime now) {
        GlobalSuppression s = suppressions.findById(p.getEmail()).orElse(null);
        JourneyBucket bucket = JourneyBucket.UNSUBSCRIBED;
        if (s != null && "BOUNCE".equals(s.getReason())) bucket = JourneyBucket.BOUNCED;
        if (s != null && "COMPLAINT".equals(s.getReason())) bucket = JourneyBucket.COMPLAINED;
        p.promoteBucket(bucket, now);
        log(journey.getId(), p.getId(), p.getCurrentNodeId(), p.getEmail(), JourneyEvent.EXITED,
                "Suppressed before this step could send, filed under " + bucket.getLabel() + ".");
    }

    // ==================================================================
    // Sending
    // ==================================================================

    /**
     * The campaign that carries one node's mail on one pass round a loop.
     *
     * Keyed on iteration as well as node because campaign_recipient is unique on
     * (campaignId, subscriberId): re-firing the same node for the same person needs a
     * different campaign, and the iteration supplies one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Campaign campaignFor(Journey journey, JourneyNode node, int iteration) {
        JourneySend existing = sends.findByNodeIdAndIterationNo(node.getId(), iteration).orElse(null);
        if (existing != null) {
            Campaign found = campaigns.findById(existing.getCampaignId()).orElse(null);
            if (found != null) return found;
        }

        String name = "[journey] " + journey.getName() + " / " + node.getName()
                + (iteration > 0 ? " (pass " + (iteration + 1) + ")" : "");
        if (name.length() > 240) name = name.substring(0, 240);

        Campaign campaign = campaigns.findByName(name).orElse(null);
        if (campaign == null) {
            campaign = new Campaign(name, journey.getCreatedBy());
            campaign.setSubject(node.getSubject());
            campaign.setPreheader(node.getPreheader());
            campaign.setFromName(node.getFromName() != null ? node.getFromName() : journey.getFromName());
            campaign.setReplyTo(node.getReplyTo() != null ? node.getReplyTo() : journey.getReplyTo());
            campaign.setHtmlBody(node.getHtmlBody());
            campaign.setTrackOpens(node.isTrackOpens());
            campaign.setTrackClicks(node.isTrackClicks());
            // SENDING rather than SENT: people trickle into a journey campaign over
            // days, so it is never "finished" the way a blast is, and marking it SENT
            // would let the composer offer to edit a campaign that is still sending.
            campaign.setStatus("SENDING");
            campaign.setStartedAt(LocalDateTime.now());
            campaign = campaigns.save(campaign);
        }

        sends.save(new JourneySend(journey.getId(), node.getId(), iteration, campaign.getId()));
        return campaign;
    }

    /** Adds one person to a journey campaign. Null when they are already on it. */
    private CampaignRecipient enrol(Campaign campaign, JourneyParticipant p) {
        try {
            CampaignRecipient recipient = new CampaignRecipient(
                    campaign.getId(), p.getSubscriberId(), p.getEmail(), p.getName());
            return recipients.save(recipient);
        } catch (RuntimeException alreadyThere) {
            return null;
        }
    }

    /**
     * One message. Mirrors CampaignService.sendOne deliberately: same merge fields,
     * same subject rendering, same tracking injection, same message log entries. A
     * journey message and a blast message must be the same message.
     */
    private void sendOne(Journey journey, Campaign campaign, CampaignRecipient recipient,
                         JourneyParticipant p, LocalDateTime now) {
        long startedNanos = System.nanoTime();
        Subscriber subscriber = subscribers.findById(p.getSubscriberId()).orElse(null);

        Map<String, String> merge = new HashMap<>();
        merge.put("NAME", recipient.getName());
        merge.put("EMAIL", recipient.getEmail());
        merge.put("FIRST_NAME", firstWord(recipient.getName()));
        merge.put("LAST_NAME", subscriber == null ? "" : nz(subscriber.getLastName()));
        merge.put("PHONE", subscriber == null ? "" : nz(subscriber.getPhone()));
        merge.put("COMPANY", subscriber == null ? "" : nz(subscriber.getCompany()));

        try {
            String html = ses.renderMarketing(campaign.getHtmlBody(), recipient.getToken(), merge,
                    campaign.getPreheader(), campaign.isTrackOpens(), campaign.isTrackClicks());
            String subject = ses.renderSubject(campaign.getSubject(), merge);

            String messageId = ses.send(new SesSender.Outgoing(
                    recipient.getEmail(), subject, html,
                    campaign.getFromName(), campaign.getReplyTo(),
                    ses.getAppDomain() + "/api/mailer/unsubscribe?token=" + recipient.getToken()));

            recipient.setStatus("SENT");
            recipient.setSentAt(now);
            recipient.setMessageId(messageId);
            recipients.save(recipient);

            if (subscriber != null) {
                subscriber.setTotalSent(subscriber.getTotalSent() + 1);
                subscribers.save(subscriber);
            }

            campaign.setSentCount(campaign.getSentCount() + 1);
            campaign.setTotalRecipients((int) recipients.countByCampaignId(campaign.getId()));
            campaigns.save(campaign);

            messageLog.recordSent(recipient.getEmail(), subject, messageId, campaign.getId(),
                    (System.nanoTime() - startedNanos) / 1_000_000L, journey.getCreatedBy());
        } catch (RuntimeException e) {
            recipient.setStatus("FAILED");
            recipient.setFailReason(e.getMessage());
            recipients.save(recipient);
            campaign.setFailedCount(campaign.getFailedCount() + 1);
            campaigns.save(campaign);
            messageLog.recordFailed(recipient.getEmail(), campaign.getSubject(), campaign.getId(),
                    e.getMessage(), (System.nanoTime() - startedNanos) / 1_000_000L, journey.getCreatedBy());
            throw e;
        }
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    private void pause(Journey journey, String reason) {
        journey.setStatus(Journey.PAUSED);
        journey.setPauseReason(reason);
        journeys.save(journey);
        log(journey.getId(), null, null, null, JourneyEvent.CAPPED, reason);
        System.err.println("Journey " + journey.getName() + " paused: " + reason);
    }

    /** Everyone still moving when the deadline arrives keeps their sheet and stops. */
    private int closeOutAtDeadline(Journey journey, LocalDateTime now) {
        int closed = 0;
        while (true) {
            List<JourneyParticipant> batch = participants.findDue(journey.getId(),
                    now.plusYears(50), PageRequest.of(0, 500));
            if (batch.isEmpty()) break;
            for (JourneyParticipant p : batch) {
                p.exit("DEADLINE", now);
                participants.save(p);
                closed++;
            }
            if (batch.size() < 500) break;
        }
        journey.setStatus(Journey.COMPLETED);
        journey.setCompletedAt(now);
        journeys.save(journey);
        log(journey.getId(), null, null, null, JourneyEvent.EXITED,
                "Deadline reached. " + closed + " people were still in flight and were closed out.");
        return closed;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * A stable bucket in 0..9999 for one person at one node.
     *
     * SHA-256 rather than String.hashCode because hashCode is 32 bits with poor
     * avalanche on the sequential integers subscriber ids actually are, and it
     * clusters badly under a modulo. This is specified byte for byte, so the same
     * person lands in the same arm on every JVM, forever.
     */
    int stableBucket(String nodeKey, Long subscriberId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((nodeKey + ":" + subscriberId + ":" + assignmentSalt)
                    .getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) value = (value << 8) | (hash[i] & 0xFFL);
            return (int) Long.remainderUnsigned(value, 10_000L);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
        }
    }

    void log(Long journeyId, Long participantId, Long nodeId, String email, String type, String detail) {
        try {
            events.save(new JourneyEvent(journeyId, participantId, nodeId, email, type, detail));
        } catch (Exception ignored) {
            // An audit row that cannot be written must never break the send it describes.
        }
    }

    /** The graph, loaded once per tick instead of once per participant. */
    public Graph loadGraph(Long journeyId) {
        return new Graph(nodes.findByJourneyIdOrderByStageAscSortOrderAsc(journeyId),
                edges.findByJourneyIdOrderBySortOrderAsc(journeyId));
    }

    public static final class Graph {
        private final Map<Long, JourneyNode> byId = new LinkedHashMap<>();
        private final Map<Long, List<JourneyEdge>> out = new HashMap<>();
        private final List<JourneyNode> all;

        Graph(List<JourneyNode> nodes, List<JourneyEdge> edges) {
            this.all = nodes;
            for (JourneyNode n : nodes) byId.put(n.getId(), n);
            for (JourneyEdge e : edges) {
                out.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>()).add(e);
            }
        }

        public JourneyNode node(Long id) { return id == null ? null : byId.get(id); }
        public List<JourneyNode> all() { return all; }

        public List<JourneyNode> byType(String type) {
            List<JourneyNode> matched = new ArrayList<>();
            for (JourneyNode n : all) if (type.equals(n.getType())) matched.add(n);
            return matched;
        }

        public List<JourneyEdge> outgoing(Long nodeId) {
            List<JourneyEdge> list = out.get(nodeId);
            if (list == null) return List.of();
            // The exhausted escape hatch is not a branch anybody takes by matching; it
            // is reached only when a loop runs out, so it must not shadow a real one.
            List<JourneyEdge> usable = new ArrayList<>(list.size());
            for (JourneyEdge e : list) if (!e.isExhausted()) usable.add(e);
            return usable;
        }

        public JourneyEdge exhaustedEdge(Long nodeId) {
            List<JourneyEdge> list = out.get(nodeId);
            if (list == null) return null;
            for (JourneyEdge e : list) if (e.isExhausted()) return e;
            return null;
        }
    }

    /** What one node handler decided: whether anything changed, and whether to chain on. */
    private record Outcome(boolean changed, boolean chain, String note) {
        static Outcome stop() { return new Outcome(false, false, null); }
        static Outcome stop(String note) { return new Outcome(false, false, note); }
        static Outcome stopChanged() { return new Outcome(true, false, null); }
        static Outcome chainChanged() { return new Outcome(true, true, null); }
    }

    private static final Set<String> HONORIFICS = Set.of(
            "dr", "doctor", "prof", "professor", "mr", "mrs", "ms", "miss", "shri", "smt", "sri");

    /** Kept in step with CampaignService so a journey greeting reads the same as a blast one. */
    private static String firstWord(String s) {
        if (s == null || s.isBlank()) return "there";
        String[] parts = s.trim().split("\\s+");
        String head = parts[0];
        String bare = head.endsWith(".") ? head.substring(0, head.length() - 1) : head;
        if (HONORIFICS.contains(bare.toLowerCase(Locale.ROOT))) {
            return parts.length > 1 ? head + " " + parts[1] : "there";
        }
        return head;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }

    static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toMinutes();
    }
}
