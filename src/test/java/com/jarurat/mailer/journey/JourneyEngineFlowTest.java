package com.jarurat.mailer.journey;

import com.jarurat.mailer.models.*;
import com.jarurat.mailer.repositories.*;
import com.jarurat.mailer.services.SesSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The user's own scenario, run end to end against a real database.
 *
 * Ten doctors, one stage-one email, a condition judged afterwards, and a loop that
 * nudges the people who never opened. The assertions are about the two properties
 * that are hard to get right and impossible to eyeball: that every person ends up in
 * exactly one sheet, and that the loop terminates.
 *
 * SES is the only thing mocked. Everything else - the campaign materialisation, the
 * recipient snapshot, the sheets, the caps - is the real code path.
 */
@SpringBootTest
class JourneyEngineFlowTest {

    @Autowired JourneyEngine engine;
    @Autowired JourneyService service;
    @Autowired JourneyRepository journeys;
    @Autowired JourneyNodeRepository nodes;
    @Autowired JourneyEdgeRepository edges;
    @Autowired JourneyParticipantRepository participants;
    @Autowired JourneySendRepository sends;

    @Autowired MailingListRepository lists;
    @Autowired SubscriberRepository subscribers;
    @Autowired ListMemberRepository listMembers;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;

    @MockitoBean SesSender ses;

    private Journey journey;
    private JourneyNode source, stageOne, condition, nudge, goalExit, otherExit;
    private MailingList doctors;

    @BeforeEach
    void setUp() {
        when(ses.renderMarketing(anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn("<html><body>rendered</body></html>");
        when(ses.renderTransactional(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        when(ses.send(any())).thenReturn("test-message-id");
        when(ses.getAppDomain()).thenReturn("https://mailer.example.com");

        // A fresh audience per test, because ddl-auto=create-drop only resets between
        // classes and these tests must not see each other's people.
        String stamp = String.valueOf(System.nanoTime());
        doctors = lists.save(new MailingList("Indian doctors " + stamp, "test", "IMPORT", "tester"));
        for (int i = 1; i <= 10; i++) {
            Subscriber s = subscribers.save(
                    new Subscriber("doctor" + i + "." + stamp + "@example.com", "Dr. Person", "" + i, "test"));
            listMembers.save(new ListMember(doctors.getId(), s.getId()));
        }

        journey = journeys.save(new Journey("Trial Finder Launch " + stamp, "tester"));
        journey.setMinGapHours(0);        // the gap guard has its own test; it is not this one
        journey.setMaxLoopIterations(2);
        journey.setMaxEmailsPerParticipant(5);
        journey.setQuietStartHour(0);
        journey.setQuietEndHour(0);       // no quiet window, so timing is not a variable here
        journeys.save(journey);

        source = node(JourneyNode.SOURCE, "Indian doctors");
        source.setSourceListId(doctors.getId());
        nodes.save(source);

        stageOne = node(JourneyNode.EMAIL, "Stage 1");
        stageOne.setSubject("What if every trial was one search away?");
        stageOne.setHtmlBody("<p>Hello {{FIRST_NAME}}</p>");
        nodes.save(stageOne);

        condition = node(JourneyNode.CONDITION, "What happened?");
        condition.setEvaluateAfterMinutes(2880);
        nodes.save(condition);

        nudge = node(JourneyNode.EMAIL, "Nudge");
        nudge.setSubject("One more time");
        nudge.setHtmlBody("<p>Did you see this?</p>");
        nodes.save(nudge);

        goalExit = node(JourneyNode.EXIT, "Reached the goal");
        goalExit.setExitBucket(JourneyBucket.CLICKED.name());
        nodes.save(goalExit);

        otherExit = node(JourneyNode.EXIT, "Everyone else out");
        nodes.save(otherExit);

        edge(source, stageOne, null, false);
        edge(stageOne, condition, null, false);
        edge(condition, goalExit, ConditionType.CLICKED.name(), false);
        edge(condition, nudge, ConditionType.NOT_OPENED.name(), false);
        edge(condition, otherExit, ConditionType.ELSE.name(), false);
        edge(nudge, condition, null, true);           // the loop the user asked for
    }

    // ==================================================================

    @Test
    @DisplayName("validation refuses a condition with no catch-all branch")
    void refusesAConditionWithNoElse() {
        edges.findByFromNodeIdOrderBySortOrderAsc(condition.getId()).stream()
                .filter(e -> ConditionType.ELSE.name().equals(e.getCondition()))
                .forEach(edges::delete);

        List<JourneyService.Finding> findings = service.validate(journey.getId());

        assertThat(findings)
                .as("a person matching nothing would have nowhere to go, which must be impossible by construction")
                .anyMatch(f -> "CONDITION_NO_ELSE".equals(f.code())
                        && JourneyService.BLOCK.equals(f.severity()));
        assertThat(JourneyService.blocked(findings)).isTrue();
    }

    @Test
    @DisplayName("a valid journey activates and pins each condition to the email above it")
    void activatesAndPinsTheMeasuredEmail() {
        List<JourneyService.Finding> findings = service.activate(journey.getId());

        assertThat(JourneyService.blocked(findings)).isFalse();
        assertThat(journeys.findById(journey.getId()).orElseThrow().getStatus())
                .isEqualTo(Journey.ACTIVE);
        assertThat(nodes.findById(condition.getId()).orElseThrow().getMeasuresNodeId())
                .as("pinned at activation so a later edit cannot repoint a running condition")
                .isEqualTo(stageOne.getId());
    }

    @Test
    @DisplayName("the first pass admits the base sheet and sends stage one to all of them")
    void firstPassSendsStageOne() {
        service.activate(journey.getId());

        Map<String, Object> result = engine.runOne(journey.getId());

        assertThat(result.get("admitted")).isEqualTo(10);
        assertThat(participants.countByJourneyId(journey.getId())).isEqualTo(10);

        JourneySend send = sends.findByNodeIdAndIterationNo(stageOne.getId(), 0).orElseThrow();
        Campaign campaign = campaigns.findById(send.getCampaignId()).orElseThrow();
        assertThat(recipients.countByCampaignIdAndStatus(campaign.getId(), "SENT"))
                .as("a journey email is an ordinary campaign, so it produces ordinary recipient rows")
                .isEqualTo(10);

        assertThat(participants.findByJourneyIdOrderByEnteredAtAsc(journey.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .allSatisfy(p -> {
                    assertThat(p.getCurrentNodeId()).isEqualTo(condition.getId());
                    assertThat(p.getEmailsSent()).isEqualTo(1);
                    assertThat(p.getNextRunAt())
                            .as("the clock is per person: 48h after it reached THEM")
                            .isAfter(LocalDateTime.now().plusDays(1));
                });
    }

    @Test
    @DisplayName("nobody is admitted twice, however many passes run")
    void admissionIsIdempotent() {
        service.activate(journey.getId());

        engine.runOne(journey.getId());
        Map<String, Object> second = engine.runOne(journey.getId());

        assertThat(second.get("admitted")).isEqualTo(0);
        assertThat(participants.countByJourneyId(journey.getId())).isEqualTo(10);
    }

    @Test
    @DisplayName("the condition files everyone in exactly one sheet and routes them accordingly")
    void conditionSplitsTheSheet() {
        service.activate(journey.getId());
        engine.runOne(journey.getId());

        // Two people click, three open without clicking, five do nothing.
        List<JourneyParticipant> everyone = allParticipants();
        markClicked(everyone.get(0));
        markClicked(everyone.get(1));
        markOpened(everyone.get(2));
        markOpened(everyone.get(3));
        markOpened(everyone.get(4));

        fastForward();
        engine.runOne(journey.getId());

        assertThat(countIn(JourneyBucket.CLICKED)).isEqualTo(2);
        assertThat(countIn(JourneyBucket.OPENED_NOT_CLICKED)).isEqualTo(3);
        assertThat(countIn(JourneyBucket.NOT_OPENED)).isEqualTo(5);

        assertThat(countIn(JourneyBucket.CLICKED) + countIn(JourneyBucket.OPENED_NOT_CLICKED)
                + countIn(JourneyBucket.NOT_OPENED))
                .as("every person is on exactly one sheet, and the sheets account for all of them")
                .isEqualTo(10);

        // The five who did nothing were routed to the nudge and it went out.
        JourneySend nudgeSend = sends.findByNodeIdAndIterationNo(nudge.getId(), 0).orElseThrow();
        assertThat(recipients.countByCampaignIdAndStatus(nudgeSend.getCampaignId(), "SENT")).isEqualTo(5);

        // The two who clicked left through the goal exit; the three who only opened
        // fell to the catch-all, because this flowchart has no branch for them.
        assertThat(activeCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("a late open moves someone off the not-opened sheet and out of the loop")
    void aLateOpenMovesSheets() {
        service.activate(journey.getId());
        engine.runOne(journey.getId());
        fastForward();
        engine.runOne(journey.getId());                    // everyone lands on NOT_OPENED, gets the nudge

        assertThat(countIn(JourneyBucket.NOT_OPENED)).isEqualTo(10);

        // One of them opens the nudge. The sheets only ever move up, so they should
        // leave "did not open" and land on "opened, no click". Re-read them first:
        // the nudge moved their measured message, so a stale copy would mark the
        // stage-one send as opened instead.
        JourneyParticipant lateReader = participants.findById(allParticipants().get(0).getId()).orElseThrow();
        markOpened(lateReader);

        fastForward();
        engine.runOne(journey.getId());

        JourneyParticipant after = participants.findById(lateReader.getId()).orElseThrow();
        assertThat(after.getBucket()).isEqualTo(JourneyBucket.OPENED_NOT_CLICKED);
        assertThat(countIn(JourneyBucket.NOT_OPENED)).isEqualTo(9);
    }

    @Test
    @DisplayName("the loop stops at its cap instead of mailing forever")
    void theLoopTerminates() {
        service.activate(journey.getId());
        engine.runOne(journey.getId());

        // Nobody ever responds. Run far more passes than the cap allows.
        for (int pass = 0; pass < 8; pass++) {
            fastForward();
            engine.runOne(journey.getId());
        }

        assertThat(activeCount())
                .as("with a loop cap of 2, nobody can still be going round after eight passes")
                .isZero();

        // The cap counts trips round the loop, not messages. The first nudge is the
        // condition's own branch; the cap then allows two more. So the worst case is
        // stage one plus three nudges, and the absolute email cap bounds it either way.
        assertThat(allParticipants())
                .allSatisfy(p -> {
                    assertThat(p.getLoopCount()).isLessThanOrEqualTo(journey.getMaxLoopIterations());
                    assertThat(p.getEmailsSent())
                            .as("stage one, the first nudge, and one nudge per trip round the loop")
                            .isLessThanOrEqualTo(2 + journey.getMaxLoopIterations());
                    assertThat(p.getEmailsSent())
                            .isLessThanOrEqualTo(journey.getMaxEmailsPerParticipant());
                });

        assertThat(countIn(JourneyBucket.NOT_OPENED)).isEqualTo(10);
    }

    @Test
    @DisplayName("each pass round the loop gets its own campaign so nobody is enrolled twice")
    void eachLoopPassIsItsOwnCampaign() {
        service.activate(journey.getId());
        engine.runOne(journey.getId());
        for (int pass = 0; pass < 4; pass++) {
            fastForward();
            engine.runOne(journey.getId());
        }

        // campaign_recipient is unique on (campaign, subscriber), so re-firing the
        // same node needs a different campaign. Iteration is what supplies one.
        assertThat(sends.findByNodeIdAndIterationNo(nudge.getId(), 0)).isPresent();
        assertThat(sends.findByNodeIdAndIterationNo(nudge.getId(), 1)).isPresent();
        assertThat(sends.findByNodeIdAndIterationNo(nudge.getId(), 2))
                .as("two trips round the loop are allowed, so iterations 0, 1 and 2 exist")
                .isPresent();
        assertThat(sends.findByNodeIdAndIterationNo(nudge.getId(), 3))
                .as("a third trip is over the cap, so its campaign must never be created")
                .isEmpty();
    }

    @Test
    @DisplayName("someone who unsubscribes mid-journey is filed and dropped immediately")
    void unsubscribeEndsIt() {
        service.activate(journey.getId());
        engine.runOne(journey.getId());

        JourneyParticipant leaver = allParticipants().get(0);
        markOpened(leaver);                                   // they read it, then opted out

        // The suppression list is the authority the engine consults, exactly as the
        // ordinary send path does.
        globalSuppress(leaver.getEmail());

        fastForward();
        engine.runOne(journey.getId());

        JourneyParticipant after = participants.findById(leaver.getId()).orElseThrow();
        assertThat(after.getBucket())
                .as("an unsubscribe outranks the open that came before it")
                .isEqualTo(JourneyBucket.UNSUBSCRIBED);
        assertThat(after.isActive()).isFalse();
    }

    @Test
    @DisplayName("the sheets can be handed back as a mailing list")
    void sheetsBecomeAudiences() {
        service.activate(journey.getId());
        engine.runOne(journey.getId());
        fastForward();
        engine.runOne(journey.getId());

        Map<String, Object> saved = service.saveSheetAsList(
                journey.getId(), JourneyBucket.NOT_OPENED.name(), "Never opened - test");

        assertThat(saved.get("added")).isEqualTo(10);
        assertThat(listMembers.countByListId(((Number) saved.get("id")).longValue())).isEqualTo(10);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    @Autowired GlobalSuppressionRepository globalSuppressions;

    private void globalSuppress(String email) {
        globalSuppressions.save(new GlobalSuppression(email, "UNSUBSCRIBED"));
    }

    private JourneyNode node(String type, String name) {
        return new JourneyNode(journey.getId(), type.toLowerCase() + "-" + name.hashCode(), type, name);
    }

    private void edge(JourneyNode from, JourneyNode to, String condition, boolean loop) {
        JourneyEdge e = new JourneyEdge(journey.getId(), from.getId(), to.getId());
        e.setCondition(condition);
        e.setLoopBack(loop);
        edges.save(e);
    }

    private List<JourneyParticipant> allParticipants() {
        return participants.findByJourneyIdOrderByEnteredAtAsc(journey.getId(),
                org.springframework.data.domain.PageRequest.of(0, 50)).getContent();
    }

    private long activeCount() {
        return participants.countByJourneyIdAndState(journey.getId(), JourneyParticipant.ACTIVE);
    }

    private long countIn(JourneyBucket bucket) {
        return participants.countByJourneyIdAndBucket(journey.getId(), bucket.name());
    }

    /**
     * openedAt and lastClickedAt are only ever written by the classifier's HUMAN path,
     * so setting them directly is the same thing a real verified open would do.
     */
    private void markOpened(JourneyParticipant p) {
        CampaignRecipient r = recipients.findById(p.getMeasuredRecipientId()).orElseThrow();
        r.setOpenedAt(LocalDateTime.now());
        r.setOpenCount(1);
        recipients.save(r);
    }

    private void markClicked(JourneyParticipant p) {
        CampaignRecipient r = recipients.findById(p.getMeasuredRecipientId()).orElseThrow();
        r.setOpenedAt(LocalDateTime.now());
        r.setLastClickedAt(LocalDateTime.now());
        r.setClickCount(1);
        recipients.save(r);
    }

    /**
     * Runs a multi-day journey in one test by moving the past backwards.
     *
     * Pulling nextRunAt forward is not enough on its own, and finding that out is
     * worth recording: a condition's clock is anchored to when the message reached
     * that person, so it recomputes its own due time from the send and pushes itself
     * straight back out to 48 hours. To simulate time passing you have to backdate
     * the send, which is exactly the per-participant clock the feature promises.
     */
    private void fastForward() {
        LocalDateTime past = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
        for (JourneyParticipant p : allParticipants()) {
            if (!p.isActive()) continue;
            if (p.getMeasuredRecipientId() != null) {
                recipients.findById(p.getMeasuredRecipientId()).ifPresent(r -> {
                    if (r.getSentAt() != null) { r.setSentAt(past); recipients.save(r); }
                });
            }
            p.setLastSendAt(past);
            p.setNextRunAt(past);
            participants.save(p);
        }
    }
}
