package com.jarurat.mailer.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket ladder is what makes "one sheet per condition" true. Every test here
 * is really the same question asked from a different angle: can the order two
 * signals happen to arrive in ever change where a person ends up?
 */
class JourneyBucketTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 8, 25, 10, 0);

    @Test
    @DisplayName("a click outranks an open, whichever arrives first")
    void clickBeatsOpen() {
        JourneyParticipant openedThenClicked = participant();
        openedThenClicked.promoteBucket(JourneyBucket.OPENED_NOT_CLICKED, T);
        openedThenClicked.promoteBucket(JourneyBucket.CLICKED, T.plusMinutes(5));

        JourneyParticipant clickedThenOpened = participant();
        clickedThenOpened.promoteBucket(JourneyBucket.CLICKED, T);
        clickedThenOpened.promoteBucket(JourneyBucket.OPENED_NOT_CLICKED, T.plusMinutes(5));

        assertThat(openedThenClicked.getBucket()).isEqualTo(JourneyBucket.CLICKED);
        assertThat(clickedThenOpened.getBucket()).isEqualTo(JourneyBucket.CLICKED);
    }

    @Test
    @DisplayName("an unsubscribe outranks every engagement signal")
    void unsubscribeBeatsEngagement() {
        JourneyParticipant p = participant();
        p.promoteBucket(JourneyBucket.CLICKED, T);
        p.promoteBucket(JourneyBucket.UNSUBSCRIBED, T.plusHours(1));

        assertThat(p.getBucket()).isEqualTo(JourneyBucket.UNSUBSCRIBED);
        assertThat(p.isActive()).isFalse();
        assertThat(p.getExitReason()).isEqualTo("BUCKET_UNSUBSCRIBED");
    }

    @Test
    @DisplayName("a person never moves back down the ladder")
    void neverDemoted() {
        JourneyParticipant p = participant();
        p.promoteBucket(JourneyBucket.CLICKED, T);

        assertThat(p.promoteBucket(JourneyBucket.NOT_OPENED, T.plusDays(1))).isFalse();
        assertThat(p.promoteBucket(JourneyBucket.OPENED_NOT_CLICKED, T.plusDays(2))).isFalse();
        assertThat(p.getBucket()).isEqualTo(JourneyBucket.CLICKED);
    }

    @Test
    @DisplayName("clicking is a goal, not a forced exit: the flowchart decides")
    void goalDoesNotExitOnItsOwn() {
        JourneyParticipant p = participant();
        p.promoteBucket(JourneyBucket.CLICKED, T);

        assertThat(p.getBucket().isGoal()).isTrue();
        assertThat(p.getBucket().mustStop()).isFalse();
        assertThat(p.isActive())
                .as("a journey that keeps talking to people who clicked is a legitimate design")
                .isTrue();
    }

    @Test
    @DisplayName("a bounce and a complaint stop the flow whatever the flowchart says")
    void deliverabilitySignalsForceAnExit() {
        JourneyParticipant bounced = participant();
        bounced.promoteBucket(JourneyBucket.BOUNCED, T);
        assertThat(bounced.isActive()).isFalse();

        JourneyParticipant complained = participant();
        complained.promoteBucket(JourneyBucket.COMPLAINED, T);
        assertThat(complained.isActive()).isFalse();
    }

    @Test
    @DisplayName("the working sheets keep people in the flow so a nudge loop is possible")
    void workingSheetsDoNotExit() {
        for (JourneyBucket bucket : new JourneyBucket[]{
                JourneyBucket.NOT_OPENED, JourneyBucket.OPENED_NOT_CLICKED,
                JourneyBucket.PRIVACY_UNKNOWN, JourneyBucket.NOT_DELIVERED}) {
            JourneyParticipant p = participant();
            p.promoteBucket(bucket, T);
            assertThat(p.isActive()).as(bucket + " must not end the journey").isTrue();
        }
    }

    @Test
    @DisplayName("ranks are strictly ordered, so no two sheets can tie")
    void ranksAreDistinct() {
        JourneyBucket[] values = JourneyBucket.values();
        for (int i = 1; i < values.length; i++) {
            assertThat(values[i].getRank())
                    .as(values[i] + " must outrank " + values[i - 1])
                    .isGreaterThan(values[i - 1].getRank());
        }
    }

    @Test
    @DisplayName("an unknown sheet name reads as NONE rather than throwing")
    void parseIsForgiving() {
        assertThat(JourneyBucket.parse(null)).isEqualTo(JourneyBucket.NONE);
        assertThat(JourneyBucket.parse("nonsense")).isEqualTo(JourneyBucket.NONE);
        assertThat(JourneyBucket.parse("  clicked ")).isEqualTo(JourneyBucket.CLICKED);
    }

    private static JourneyParticipant participant() {
        return new JourneyParticipant(1L, 42L, "doctor@example.com", "Dr. Akanksha", 7L, 1);
    }
}
