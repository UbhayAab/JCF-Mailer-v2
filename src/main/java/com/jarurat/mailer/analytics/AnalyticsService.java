package com.jarurat.mailer.analytics;

import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.repositories.CampaignRepository;
import com.jarurat.mailer.services.CampaignService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Every rate published here is computed from HUMAN events only. The MPP, proxy and bot
 * counts are reported alongside, never folded in, so a jump in the headline number
 * cannot be an Apple release note in disguise.
 */
@Service
public class AnalyticsService {

    /** Caps how far back a single request can scan, so one URL cannot table scan a year. */
    private static final int MAX_WINDOW_DAYS = 365;

    private final TrackingEventRepository events;
    private final AnalyticsAggregateRepository aggregates;
    private final CampaignRepository campaigns;
    private final OpenClassifier classifier;

    public AnalyticsService(TrackingEventRepository events,
                            AnalyticsAggregateRepository aggregates,
                            CampaignRepository campaigns,
                            OpenClassifier classifier) {
        this.events = events;
        this.aggregates = aggregates;
        this.campaigns = campaigns;
        this.classifier = classifier;
    }

    /** Inclusive of today, so days=30 means today plus the 29 before it. */
    public record Window(LocalDateTime from, LocalDateTime to, int days) {
        public static Window ofDays(int requested) {
            int days = Math.max(1, Math.min(MAX_WINDOW_DAYS, requested));
            LocalDate today = LocalDate.now();
            return new Window(today.minusDays(days - 1L).atStartOfDay(),
                    today.plusDays(1).atStartOfDay(), days);
        }
    }

    // ------------------------------------------------------------------
    // Headline
    // ------------------------------------------------------------------

    public Map<String, Object> summary(Long campaignId, int days) {
        Window w = Window.ofDays(days);

        long sent = aggregates.countSent(campaignId, w.from(), w.to());
        long bounced = aggregates.countAttributedSuppressions(campaignId, "BOUNCE", w.from(), w.to());
        long complained = aggregates.countAttributedSuppressions(campaignId, "COMPLAINT", w.from(), w.to());
        long unsubscribed = aggregates.countAttributedSuppressions(campaignId, "UNSUBSCRIBED", w.from(), w.to());
        long delivered = Math.max(0, sent - bounced);

        long reliableOpens = unique(TrackingEvent.OPEN, OpenClassification.HUMAN, campaignId, w);
        long mppOpens = unique(TrackingEvent.OPEN, OpenClassification.APPLE_MPP, campaignId, w);
        long botOpens = unique(TrackingEvent.OPEN, OpenClassification.BOT, campaignId, w);
        long proxyOpens = unique(TrackingEvent.OPEN, OpenClassification.PROXY, campaignId, w);
        long allOpens = reliableOpens + mppOpens + botOpens + proxyOpens;

        long reliableClicks = unique(TrackingEvent.CLICK, OpenClassification.HUMAN, campaignId, w);
        long botClicks = unique(TrackingEvent.CLICK, OpenClassification.BOT, campaignId, w);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", campaignId);
        out.put("windowDays", w.days());
        out.put("from", w.from().toString());
        out.put("to", w.to().toString());

        out.put("sent", sent);
        out.put("delivered", delivered);
        out.put("deliveredRate", CampaignService.rate(delivered, sent));
        // A failed or skipped recipient has no sentAt, so these two cannot be windowed.
        // The key names say "allTime" so nobody reads them as part of the window above.
        out.put("failedAllTime", aggregates.countRecipientsByStatus(campaignId, "FAILED"));
        out.put("skippedAllTime", aggregates.countRecipientsByStatus(campaignId, "SKIPPED"));

        out.put("reliableOpens", reliableOpens);
        out.put("reliableOpenHits", hits(TrackingEvent.OPEN, OpenClassification.HUMAN, campaignId, w));
        out.put("mppOpens", mppOpens);
        out.put("botOpens", botOpens);
        out.put("proxyOpens", proxyOpens);
        out.put("openRate", CampaignService.rate(reliableOpens, delivered));

        // The number the old pixel endpoint would have printed, kept next to the real one
        // so the size of the correction is visible instead of being argued about.
        out.put("unfilteredOpens", allOpens);
        out.put("unfilteredOpenRate", CampaignService.rate(allOpens, delivered));
        out.put("inflationFactor", reliableOpens == 0 ? 0
                : Math.round(allOpens * 100.0 / reliableOpens) / 100.0);

        out.put("reliableClicks", reliableClicks);
        out.put("reliableClickHits", hits(TrackingEvent.CLICK, OpenClassification.HUMAN, campaignId, w));
        out.put("botClicks", botClicks);
        out.put("clickRate", CampaignService.rate(reliableClicks, delivered));
        out.put("clickToOpenRate", CampaignService.rate(reliableClicks, reliableOpens));

        out.put("bounced", bounced);
        out.put("bounceRate", preciseRate(bounced, sent));
        out.put("complained", complained);
        out.put("complaintRate", preciseRate(complained, sent));
        out.put("unsubscribed", unsubscribed);
        out.put("unsubscribeRate", preciseRate(unsubscribed, sent));

        // SES suspends an account past roughly 5% bounce and 0.1% complaint
        out.put("bounceDangerLine", 2.0);
        out.put("complaintDangerLine", 0.1);

        out.put("attribution", "Bounces, complaints and unsubscribes are attributed by matching "
                + "the address against sends in this window. SES reports them at account level, "
                + "not per message, so a subscriber mailed by two campaigns before bouncing counts "
                + "against both.");
        return out;
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    /**
     * One entry per day in the window, zero filled, so the chart has no gaps.
     *
     * The volume line is sent, not delivered. A bounce carries only the timestamp of the
     * SES notification, not of the send that caused it, so subtracting bounces day by day
     * would charge them to the wrong day. The headline tile nets them off over the whole
     * window, where the attribution holds; this series stays sent so the two are never
     * silently different numbers under the same name.
     */
    public List<Map<String, Object>> dailySeries(Long campaignId, int days) {
        Window w = Window.ofDays(days);

        Map<LocalDate, Map<String, Object>> byDay = new LinkedHashMap<>();
        LocalDate cursor = w.from().toLocalDate();
        LocalDate last = w.to().toLocalDate();
        while (cursor.isBefore(last)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", cursor.toString());
            row.put("sent", 0L);
            row.put("reliableOpens", 0L);
            row.put("mppOpens", 0L);
            row.put("botOpens", 0L);
            row.put("reliableClicks", 0L);
            byDay.put(cursor, row);
            cursor = cursor.plusDays(1);
        }

        merge(byDay, aggregates.dailySent(campaignId, w.from(), w.to()), "sent");
        merge(byDay, events.dailyUnique(TrackingEvent.OPEN, OpenClassification.HUMAN, campaignId, w.from(), w.to()), "reliableOpens");
        merge(byDay, events.dailyUnique(TrackingEvent.OPEN, OpenClassification.APPLE_MPP, campaignId, w.from(), w.to()), "mppOpens");
        merge(byDay, events.dailyUnique(TrackingEvent.OPEN, OpenClassification.BOT, campaignId, w.from(), w.to()), "botOpens");
        merge(byDay, events.dailyUnique(TrackingEvent.CLICK, OpenClassification.HUMAN, campaignId, w.from(), w.to()), "reliableClicks");

        return new ArrayList<>(byDay.values());
    }

    /** Rows arrive as year, month, day, count. */
    private static void merge(Map<LocalDate, Map<String, Object>> byDay, List<Object[]> rows, String key) {
        for (Object[] row : rows) {
            if (row == null || row.length < 4 || row[0] == null || row[1] == null || row[2] == null) continue;
            LocalDate day = LocalDate.of(((Number) row[0]).intValue(),
                    ((Number) row[1]).intValue(), ((Number) row[2]).intValue());
            Map<String, Object> target = byDay.get(day);
            if (target != null) target.put(key, row[3] == null ? 0L : ((Number) row[3]).longValue());
        }
    }

    // ------------------------------------------------------------------
    // Breakdowns
    // ------------------------------------------------------------------

    public List<Map<String, Object>> topLinks(Long campaignId, int days, int limit) {
        Window w = Window.ofDays(days);
        List<TrackingEventRepository.LinkStat> stats = events.topLinks(
                OpenClassification.HUMAN, campaignId, w.from(), w.to(),
                PageRequest.of(0, Math.max(1, Math.min(100, limit))));

        long totalClicks = 0;
        for (TrackingEventRepository.LinkStat s : stats) totalClicks += s.getClicks();

        List<Map<String, Object>> out = new ArrayList<>();
        for (TrackingEventRepository.LinkStat s : stats) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("url", s.getUrl() == null ? "" : s.getUrl());
            row.put("clicks", s.getClicks());
            row.put("unique", s.getUniqueClicks());
            row.put("share", CampaignService.rate(s.getClicks(), totalClicks));
            out.add(row);
        }
        return out;
    }

    /** Mail client and device split, from reliable opens only. */
    public Map<String, Object> clients(Long campaignId, int days) {
        Window w = Window.ofDays(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clients", share(events.clientBreakdown(
                TrackingEvent.OPEN, OpenClassification.HUMAN, campaignId, w.from(), w.to())));
        out.put("devices", share(events.deviceBreakdown(
                TrackingEvent.OPEN, OpenClassification.HUMAN, campaignId, w.from(), w.to())));
        out.put("basis", "Reliable opens only. Apple MPP strips the client identity, so MPP "
                + "traffic cannot appear in this split at all.");
        return out;
    }

    private static List<Map<String, Object>> share(List<Object[]> rows) {
        long total = 0;
        for (Object[] row : rows) {
            if (row.length > 1 && row[1] != null) total += ((Number) row[1]).longValue();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            long count = row.length > 1 && row[1] != null ? ((Number) row[1]).longValue() : 0;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", row[0] == null ? "Unknown" : String.valueOf(row[0]));
            entry.put("count", count);
            entry.put("share", CampaignService.rate(count, total));
            out.add(entry);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Per campaign
    // ------------------------------------------------------------------

    public List<Map<String, Object>> byCampaign(int days) {
        Window w = Window.ofDays(days);

        Map<Long, String> names = new HashMap<>();
        for (Campaign c : campaigns.findAll()) names.put(c.getId(), c.getName());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : aggregates.sentByCampaign(w.from(), w.to())) {
            if (row[0] == null) continue;
            Long id = ((Number) row[0]).longValue();
            long sent = row[1] == null ? 0 : ((Number) row[1]).longValue();
            long bounced = aggregates.countAttributedSuppressions(id, "BOUNCE", w.from(), w.to());
            long delivered = Math.max(0, sent - bounced);
            long opens = unique(TrackingEvent.OPEN, OpenClassification.HUMAN, id, w);
            long mpp = unique(TrackingEvent.OPEN, OpenClassification.APPLE_MPP, id, w);
            long bots = unique(TrackingEvent.OPEN, OpenClassification.BOT, id, w);
            long clicks = unique(TrackingEvent.CLICK, OpenClassification.HUMAN, id, w);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("campaignId", id);
            entry.put("name", names.getOrDefault(id, "(deleted campaign)"));
            entry.put("sent", sent);
            entry.put("delivered", delivered);
            entry.put("bounced", bounced);
            entry.put("reliableOpens", opens);
            entry.put("mppOpens", mpp);
            entry.put("botOpens", bots);
            entry.put("openRate", CampaignService.rate(opens, delivered));
            entry.put("reliableClicks", clicks);
            entry.put("clickRate", CampaignService.rate(clicks, delivered));
            entry.put("clickToOpenRate", CampaignService.rate(clicks, opens));
            out.add(entry);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Classifier transparency
    // ------------------------------------------------------------------

    /** Lets the console show why the open rate looks lower than the old screen said. */
    public Map<String, Object> classifierInfo(Long campaignId, int days) {
        Window w = Window.ofDays(days);
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (OpenClassification c : OpenClassification.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("classification", c.name());
            entry.put("label", c.getLabel());
            entry.put("description", c.getDescription());
            entry.put("countsAsEngagement", c.countsAsEngagement());
            entry.put("opens", unique(TrackingEvent.OPEN, c, campaignId, w));
            entry.put("openHits", hits(TrackingEvent.OPEN, c, campaignId, w));
            entry.put("clicks", unique(TrackingEvent.CLICK, c, campaignId, w));
            buckets.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", w.days());
        out.put("prefetchWindowSeconds", classifier.getPrefetchWindowSeconds());
        out.put("appleNetworkRanges", classifier.getAppleNetworkCount());
        out.put("buckets", buckets);
        return out;
    }

    // ------------------------------------------------------------------

    private long unique(String eventType, OpenClassification c, Long campaignId, Window w) {
        return events.countUnique(eventType, c, campaignId, w.from(), w.to());
    }

    private long hits(String eventType, OpenClassification c, Long campaignId, Window w) {
        return events.countHits(eventType, c, campaignId, w.from(), w.to());
    }

    /**
     * Bounce and complaint rates are judged against thresholds of 2% and 0.1%, so one
     * decimal place is not enough resolution to see a problem coming.
     */
    private static double preciseRate(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 100_000.0 / whole) / 1000.0;
    }
}
