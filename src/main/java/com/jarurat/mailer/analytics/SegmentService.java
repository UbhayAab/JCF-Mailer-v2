package com.jarurat.mailer.analytics;

import com.jarurat.mailer.models.CampaignRecipient;
import com.jarurat.mailer.models.ListMember;
import com.jarurat.mailer.models.MailingList;
import com.jarurat.mailer.repositories.CampaignRecipientRepository;
import com.jarurat.mailer.repositories.CampaignRepository;
import com.jarurat.mailer.repositories.GlobalSuppressionRepository;
import com.jarurat.mailer.repositories.ListMemberRepository;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.services.CampaignService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the engagement segments into something you can act on.
 *
 * The rate math already existed. What did not was the ability to click 41% and see
 * the 41 people, export them, or mail them again. That gap is why the numbers were
 * decoration: a report you cannot act on is a report nobody checks twice.
 */
@Service
public class SegmentService {

    private final SegmentRepository segments;
    private final CampaignRecipientRepository recipients;
    private final CampaignRepository campaigns;
    private final MailingListRepository lists;
    private final ListMemberRepository listMembers;
    private final GlobalSuppressionRepository suppressions;

    public SegmentService(SegmentRepository segments,
                          CampaignRecipientRepository recipients,
                          CampaignRepository campaigns,
                          MailingListRepository lists,
                          ListMemberRepository listMembers,
                          GlobalSuppressionRepository suppressions) {
        this.segments = segments;
        this.recipients = recipients;
        this.campaigns = campaigns;
        this.lists = lists;
        this.listMembers = listMembers;
        this.suppressions = suppressions;
    }

    /**
     * Every segment with its count, split into the four that partition the audience
     * and the rest that overlap.
     *
     * The split is presented rather than hidden because the tiles do not sum to the
     * total and never will: somebody can click a link and then unsubscribe, and they
     * belong in both. A screen that quietly implies the numbers add up teaches people
     * to distrust it the first time they add them.
     */
    public Map<String, Object> summary(Long campaignId) {
        long sent = recipients.countByCampaignIdAndStatus(campaignId, "SENT");

        List<Map<String, Object>> exclusive = new ArrayList<>();
        List<Map<String, Object>> overlapping = new ArrayList<>();
        long exclusiveTotal = 0;
        long unknown = 0, opened = 0;

        for (EngagementSegment segment : EngagementSegment.values()) {
            long count = segments.countIn(campaignId, segment.name());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("segment", segment.name());
            row.put("label", segment.getLabel());
            row.put("description", segment.getDescription());
            row.put("count", count);
            row.put("share", CampaignService.rate(count, sent));
            row.put("unmailable", segment.isUnmailable());

            if (segment.isExclusive()) {
                exclusive.add(row);
                exclusiveTotal += count;
                if (segment == EngagementSegment.PRIVACY_UNKNOWN) unknown = count;
                if (segment == EngagementSegment.CLICKED
                        || segment == EngagementSegment.OPENED_NOT_CLICKED) opened += count;
            } else {
                overlapping.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", campaignId);
        out.put("sent", sent);
        out.put("exclusive", exclusive);
        out.put("overlapping", overlapping);
        out.put("exclusiveTotal", exclusiveTotal);

        // The honest open rate is a range, not a number, whenever a meaningful share
        // of the audience is behind a privacy proxy. Reporting only the lower bound
        // understates reach; reporting only the upper bound is the inflation this
        // platform already refuses to publish.
        out.put("openRateLower", CampaignService.rate(opened, sent));
        out.put("openRateUpper", CampaignService.rate(opened + unknown, sent));
        long possibleOpeners = opened + unknown;
        out.put("unknownShareOfPossibleOpeners", CampaignService.rate(unknown, possibleOpeners));
        out.put("showAsRange", possibleOpeners > 0
                && CampaignService.rate(unknown, possibleOpeners) > 10.0);

        out.put("caveat", "These groups do not add up to the total on purpose. The first four "
                + "cover everyone delivered to, exactly once each. The rest overlap with them: "
                + "somebody can click a link and then unsubscribe, and they belong in both.");
        out.put("readingCaveat", "Email cannot measure reading. An open means an image was "
                + "fetched, which a person may never have seen, and anyone reading with images "
                + "off registers nothing at all. These are the strongest behaviours that can "
                + "actually be observed, not a readership figure.");
        return out;
    }

    public List<CampaignRecipient> people(Long campaignId, EngagementSegment segment) {
        return segments.allIn(campaignId, segment.name());
    }

    /**
     * Time from send to first verified open, bucketed. This is the closest honest
     * proxy for engagement depth that email offers, and it is labelled as a proxy
     * rather than dressed up as reading time.
     */
    public Map<String, Object> timeToOpen(Long campaignId) {
        long[] edges = {60, 900, 3600, 21600, 86400, 259200, Long.MAX_VALUE};
        String[] labels = {"under a minute", "1 to 15 min", "15 min to 1 h", "1 to 6 h",
                "6 to 24 h", "1 to 3 days", "over 3 days"};
        long[] counts = new long[edges.length];
        List<Long> allSeconds = new ArrayList<>();

        for (Object[] row : segments.openTimings(campaignId)) {
            if (row[0] == null || row[1] == null) continue;
            long seconds = Duration.between((LocalDateTime) row[0], (LocalDateTime) row[1]).toSeconds();
            if (seconds < 0) continue;   // clock skew between boxes; not a real timing
            allSeconds.add(seconds);
            for (int i = 0; i < edges.length; i++) {
                if (seconds < edges[i]) { counts[i]++; break; }
            }
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("label", labels[i]);
            bucket.put("count", counts[i]);
            buckets.add(bucket);
        }

        allSeconds.sort(Long::compareTo);
        Long median = allSeconds.isEmpty() ? null : allSeconds.get(allSeconds.size() / 2);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("buckets", buckets);
        out.put("total", allSeconds.size());
        out.put("medianSeconds", median);
        out.put("medianLabel", median == null ? null : humanDuration(median));
        out.put("basis", "Verified human opens only. It tells you when this audience reads mail, "
                + "which is a real behaviour, and nothing about whether they read the words.");
        return out;
    }

    private static String humanDuration(long seconds) {
        if (seconds < 60) return seconds + " seconds";
        if (seconds < 3600) return (seconds / 60) + " minutes";
        if (seconds < 86400) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        return (seconds / 86400) + " days";
    }

    /**
     * Saves a segment as a reusable audience, which is the action that makes the
     * whole screen worth having: "everyone who opened and did nothing" becomes a
     * list you can send a follow-up to from the ordinary composer.
     */
    @Transactional
    public Map<String, Object> saveAsList(Long campaignId, EngagementSegment segment, String name) {
        if (segment.isUnmailable()) {
            throw new IllegalArgumentException("\"" + segment.getLabel() + "\" cannot become a "
                    + "mailing list. Every address in it is dead or hostile, and mailing them "
                    + "again is how a sending domain loses its reputation. Export it instead.");
        }

        String campaignName = campaigns.findById(campaignId)
                .map(c -> c.getName()).orElse("campaign " + campaignId);
        String listName = name == null || name.isBlank()
                ? campaignName + " - " + segment.getLabel() : name.trim();
        if (lists.existsByName(listName))
            throw new IllegalArgumentException("A list called \"" + listName + "\" already exists.");

        MailingList list = lists.save(new MailingList(listName,
                "People who " + segment.getLabel().toLowerCase() + " in \"" + campaignName + "\"",
                "IMPORT", AuditService.currentActor()));

        int added = 0, skipped = 0;
        for (CampaignRecipient r : segments.allIn(campaignId, segment.name())) {
            // Even a mailable segment can contain someone who has since unsubscribed,
            // because the segment describes what they did then and the suppression
            // list describes what they want now. The suppression list wins.
            if (suppressions.existsById(r.getEmail())) { skipped++; continue; }
            if (listMembers.existsByListIdAndSubscriberId(list.getId(), r.getSubscriberId())) continue;
            listMembers.save(new ListMember(list.getId(), r.getSubscriberId()));
            added++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", list.getId());
        out.put("name", listName);
        out.put("added", added);
        out.put("skipped", skipped);
        out.put("message", "Saved " + added + " people as \"" + listName + "\"."
                + (skipped > 0 ? " " + skipped + " were left out because they have since been "
                                 + "suppressed." : ""));
        return out;
    }
}
