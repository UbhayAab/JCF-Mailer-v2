package com.jarurat.mailer.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The A/B allocator and the quiet-hours shift.
 *
 * Both are pure functions on purpose, and both are tested here without Spring,
 * because the property that matters for each is one a running system cannot easily
 * demonstrate: that the same inputs always produce the same answer, on every JVM,
 * after every restart.
 */
class JourneyAllocationTest {

    private final JourneyEngine engine = new JourneyEngine(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, "test-salt");

    // ------------------------------------------------------------------
    // Variant assignment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same person at the same step always lands in the same arm")
    void assignmentIsStable() {
        for (long subscriber = 1; subscriber <= 200; subscriber++) {
            int first = engine.stableBucket("split1", subscriber);
            int again = engine.stableBucket("split1", subscriber);
            assertThat(again)
                    .as("a retry or a restart must not re-roll subscriber " + subscriber)
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("every bucket falls inside the 0..9999 range the weights are cut against")
    void bucketsAreInRange() {
        for (long subscriber = 1; subscriber <= 5000; subscriber++) {
            assertThat(engine.stableBucket("split1", subscriber)).isBetween(0, 9999);
        }
    }

    @Test
    @DisplayName("a 50/50 split lands close to even over a realistic audience")
    void fiftyFiftyIsBalanced() {
        int armA = 0;
        for (long subscriber = 1; subscriber <= 4000; subscriber++) {
            if (engine.stableBucket("split1", subscriber) < 5000) armA++;
        }
        // Well inside what chance allows at n=4000, and far outside what a clustered
        // hash would produce. String.hashCode on sequential ids fails this.
        assertThat(armA).isBetween(1850, 2150);
    }

    @Test
    @DisplayName("a person's arm at one step tells you nothing about their arm at the next")
    void stagesAreIndependent() {
        int sameSide = 0;
        for (long subscriber = 1; subscriber <= 2000; subscriber++) {
            boolean firstStage = engine.stableBucket("split1", subscriber) < 5000;
            boolean secondStage = engine.stableBucket("split2", subscriber) < 5000;
            if (firstStage == secondStage) sameSide++;
        }
        // If the node key were ignored this would be 2000. Around half is what
        // independence looks like.
        assertThat(sameSide).isBetween(900, 1100);
    }

    @Test
    @DisplayName("a different salt reshuffles the audience")
    void saltReshuffles() {
        JourneyEngine other = new JourneyEngine(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "a-different-salt");

        int moved = 0;
        for (long subscriber = 1; subscriber <= 1000; subscriber++) {
            boolean here = engine.stableBucket("split1", subscriber) < 5000;
            boolean there = other.stableBucket("split1", subscriber) < 5000;
            if (here != there) moved++;
        }
        assertThat(moved)
                .as("without a per-account salt, the same people would be arm A in every test forever")
                .isGreaterThan(300);
    }

    // ------------------------------------------------------------------
    // Quiet hours
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a send inside the quiet window is pushed forward to the morning")
    void quietHoursShiftForward() {
        Journey journey = quietJourney(21, 8);
        LocalDateTime lateNight = LocalDateTime.of(2026, 8, 25, 23, 30);

        LocalDateTime shifted = engine.applyQuietHours(journey, lateNight);

        assertThat(shifted).isAfter(lateNight);
        assertThat(shifted.getHour()).isEqualTo(8);
        assertThat(shifted.toLocalDate())
                .as("23:30 belongs to the next morning, not this one")
                .isEqualTo(lateNight.toLocalDate().plusDays(1));
    }

    @Test
    @DisplayName("an early hour shifts to the same morning, not the next")
    void earlyMorningShiftsToday() {
        Journey journey = quietJourney(21, 8);
        LocalDateTime tooEarly = LocalDateTime.of(2026, 8, 25, 4, 15);

        LocalDateTime shifted = engine.applyQuietHours(journey, tooEarly);

        assertThat(shifted).isEqualTo(LocalDateTime.of(2026, 8, 25, 8, 0));
    }

    @Test
    @DisplayName("a send outside the window is left exactly where it was")
    void daytimeIsUntouched() {
        Journey journey = quietJourney(21, 8);
        LocalDateTime midMorning = LocalDateTime.of(2026, 8, 25, 10, 37, 12);

        assertThat(engine.applyQuietHours(journey, midMorning)).isEqualTo(midMorning);
    }

    @Test
    @DisplayName("quiet hours never move a send earlier than asked for")
    void neverShiftsBackwards() {
        Journey journey = quietJourney(21, 8);
        LocalDateTime start = LocalDateTime.of(2026, 8, 25, 0, 0);
        for (int minute = 0; minute < 60 * 24; minute += 17) {
            LocalDateTime when = start.plusMinutes(minute);
            assertThat(engine.applyQuietHours(journey, when))
                    .as("a message arriving earlier than intended is worse than one arriving later")
                    .isAfterOrEqualTo(when);
        }
    }

    @Test
    @DisplayName("matching start and end hours means no quiet window at all")
    void disabledWindowIsANoOp() {
        Journey journey = quietJourney(0, 0);
        LocalDateTime middleOfTheNight = LocalDateTime.of(2026, 8, 25, 3, 0);

        assertThat(engine.applyQuietHours(journey, middleOfTheNight)).isEqualTo(middleOfTheNight);
    }

    private static Journey quietJourney(int start, int end) {
        Journey journey = new Journey("test", "tester");
        journey.setQuietStartHour(start);
        journey.setQuietEndHour(end);
        journey.setZoneId(java.time.ZoneId.systemDefault().getId());
        return journey;
    }
}
