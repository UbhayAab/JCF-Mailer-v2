package com.jarurat.mailer.analytics;

import com.jarurat.mailer.models.*;
import com.jarurat.mailer.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four questions the user actually asked, checked against fixture data where the
 * right answer is known by construction.
 *
 * The one worth reading closely is the privacy bucket. A person whose only pixel hit
 * came from Apple's pre-fetch has not been shown to have read anything, and has not
 * been shown not to have. Counting them as an opener inflates the rate; counting them
 * as a non-opener understates reach. They get their own bucket, and the test proves
 * they land in neither of the other two.
 */
@SpringBootTest
class SegmentServiceTest {

    @Autowired SegmentService service;
    @Autowired SegmentRepository segments;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired SubscriberRepository subscribers;
    @Autowired TrackingEventRepository events;
    @Autowired GlobalSuppressionRepository suppressions;
    @Autowired ListMemberRepository listMembers;

    private Campaign campaign;
    private String stamp;

    @BeforeEach
    void setUp() {
        stamp = "s" + System.nanoTime();
        campaign = campaigns.save(new Campaign("Segment test " + stamp, "tester"));
        campaign.setSubject("Hello");
        campaign.setHtmlBody("<p>Hi</p>");
        campaigns.save(campaign);
    }

    @Test
    @DisplayName("the four exclusive groups account for everybody, exactly once each")
    void exclusiveGroupsPartitionTheAudience() {
        person("clicker", true, true, false);
        person("opener1", true, false, false);
        person("opener2", true, false, false);
        person("mpp", false, false, true);      // only a machine fetched the pixel
        person("silent1", false, false, false);
        person("silent2", false, false, false);

        Map<String, Object> summary = service.summary(campaign.getId());

        assertThat(count(summary, "CLICKED")).isEqualTo(1);
        assertThat(count(summary, "OPENED_NOT_CLICKED")).isEqualTo(2);
        assertThat(count(summary, "PRIVACY_UNKNOWN")).isEqualTo(1);
        assertThat(count(summary, "NOT_OPENED")).isEqualTo(2);

        assertThat(summary.get("exclusiveTotal"))
                .as("no double counting and nobody unaccounted for")
                .isEqualTo(6L);
        assertThat(summary.get("sent")).isEqualTo(6L);
    }

    @Test
    @DisplayName("a machine-only open is neither an open nor a non-open")
    void privacyProtectedIsItsOwnAnswer() {
        person("mpp", false, false, true);

        assertThat(segments.countIn(campaign.getId(), "OPENED_NOT_CLICKED")).isZero();
        assertThat(segments.countIn(campaign.getId(), "NOT_OPENED"))
                .as("counting a privacy proxy as a non-opener would understate real reach")
                .isZero();
        assertThat(segments.countIn(campaign.getId(), "PRIVACY_UNKNOWN")).isEqualTo(1);
    }

    @Test
    @DisplayName("the open rate is reported as a range when the doubt is large")
    void openRateIsARangeWhenPrivacyBitesHard() {
        person("opener", true, false, false);
        for (int i = 0; i < 4; i++) person("mpp" + i, false, false, true);

        Map<String, Object> summary = service.summary(campaign.getId());

        assertThat(summary.get("openRateLower")).isEqualTo(20.0);   // 1 of 5
        assertThat(summary.get("openRateUpper")).isEqualTo(100.0);  // 5 of 5 at most
        assertThat(summary.get("showAsRange"))
                .as("four of the five possible openers cannot be verified either way")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("a click always counts as engagement even with no pixel load")
    void aClickWithoutAPixelStillCounts() {
        // Images off means the pixel never fires, but the link click still does. The
        // send path backfills openedAt from the click, which is why this person lands
        // in CLICKED rather than in the privacy bucket.
        person("imagesOff", true, true, false);

        assertThat(segments.countIn(campaign.getId(), "CLICKED")).isEqualTo(1);
        assertThat(segments.countIn(campaign.getId(), "NOT_OPENED")).isZero();
    }

    @Test
    @DisplayName("the overlapping groups genuinely overlap and are not folded in")
    void overlappingGroupsAreSeparate() {
        CampaignRecipient p = person("clickedThenLeft", true, true, false);
        suppressions.save(new GlobalSuppression(p.getEmail(), "UNSUBSCRIBED"));

        assertThat(segments.countIn(campaign.getId(), "CLICKED"))
                .as("they did click, and unsubscribing later does not undo that")
                .isEqualTo(1);
        assertThat(segments.countIn(campaign.getId(), "UNSUBSCRIBED")).isEqualTo(1);
    }

    @Test
    @DisplayName("a segment becomes a mailing list, minus anyone since suppressed")
    void segmentBecomesAnAudience() {
        person("a", true, false, false);
        person("b", true, false, false);
        CampaignRecipient gone = person("c", true, false, false);
        suppressions.save(new GlobalSuppression(gone.getEmail(), "UNSUBSCRIBED"));

        Map<String, Object> saved = service.saveAsList(campaign.getId(),
                EngagementSegment.OPENED_NOT_CLICKED, "Opened no click " + stamp);

        assertThat(saved.get("added")).isEqualTo(2);
        assertThat(saved.get("skipped"))
                .as("the segment says what they did then; the suppression list says what they want now")
                .isEqualTo(1);
        assertThat(listMembers.countByListId(((Number) saved.get("id")).longValue())).isEqualTo(2);
    }

    @Test
    @DisplayName("a bounced group refuses to become a mailing list")
    void bouncedCannotBecomeAList() {
        CampaignRecipient dead = person("dead", true, false, false);
        suppressions.save(new GlobalSuppression(dead.getEmail(), "BOUNCE"));

        assertThatThrownBy(() -> service.saveAsList(campaign.getId(), EngagementSegment.BOUNCED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot become a mailing list");
    }

    @Test
    @DisplayName("time to open is bucketed from verified opens only")
    void timeToOpenBuckets() {
        openedAfter("fast", 30);        // under a minute
        openedAfter("medium", 1800);    // 15 min to 1 h
        openedAfter("slow", 90000);     // 1 to 3 days

        Map<String, Object> timing = service.timeToOpen(campaign.getId());

        assertThat(timing.get("total")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) timing.get("buckets");
        assertThat(buckets.get(0).get("count")).isEqualTo(1L);   // under a minute
        assertThat(buckets.get(2).get("count")).isEqualTo(1L);   // 15 min to 1 h
        assertThat(buckets.get(5).get("count")).isEqualTo(1L);   // 1 to 3 days
    }

    // ------------------------------------------------------------------

    private long count(Map<String, Object> summary, String segment) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("exclusive");
        return rows.stream().filter(r -> segment.equals(r.get("segment")))
                .mapToLong(r -> (Long) r.get("count")).findFirst().orElse(-1);
    }

    private CampaignRecipient person(String tag, boolean opened, boolean clicked, boolean machineOnly) {
        Subscriber s = subscribers.save(new Subscriber(tag + stamp + "@example.com", "Dr", tag, "test"));
        CampaignRecipient r = new CampaignRecipient(campaign.getId(), s.getId(), s.getEmail(), "Dr " + tag);
        r.setStatus("SENT");
        r.setSentAt(LocalDateTime.now().minusHours(2));
        if (opened) r.setOpenedAt(LocalDateTime.now().minusHours(1));
        if (clicked) r.setLastClickedAt(LocalDateTime.now().minusMinutes(30));
        r = recipients.save(r);

        if (machineOnly) {
            events.save(new TrackingEvent(TrackingEvent.OPEN, OpenClassification.APPLE_MPP,
                    campaign.getId(), s.getId(), r.getId(), s.getEmail(), null,
                    "Mozilla/5.0 AppleWebKit", "17.0.0.1", "Apple Mail", "desktop", 2L, "prefetch"));
        }
        return r;
    }

    private void openedAfter(String tag, long seconds) {
        Subscriber s = subscribers.save(new Subscriber(tag + stamp + "@example.com", "Dr", tag, "test"));
        CampaignRecipient r = new CampaignRecipient(campaign.getId(), s.getId(), s.getEmail(), "Dr " + tag);
        r.setStatus("SENT");
        LocalDateTime sent = LocalDateTime.now().minusDays(5);
        r.setSentAt(sent);
        r.setOpenedAt(sent.plusSeconds(seconds));
        recipients.save(r);
    }
}
