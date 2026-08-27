package com.jarurat.mailer.analytics;

import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jarurat.mailer.controllers.AudienceApi.csv;

/**
 * Read side of the honest numbers. Every response is scoped by a day window and
 * optionally by campaign, and every rate in them comes from HUMAN events only.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsApi {

    private final AnalyticsService analytics;
    private final OpenTrackingService tracking;
    private final AuditService audit;
    private final SegmentService segments;
    private final SegmentRepository segmentRepository;

    public AnalyticsApi(AnalyticsService analytics, OpenTrackingService tracking, AuditService audit,
                        SegmentService segments, SegmentRepository segmentRepository) {
        this.analytics = analytics;
        this.tracking = tracking;
        this.audit = audit;
        this.segments = segments;
        this.segmentRepository = segmentRepository;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> summary(@RequestParam(required = false) Long campaignId,
                                       @RequestParam(defaultValue = "30") int days) {
        return analytics.summary(campaignId, days);
    }

    @GetMapping("/series")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public List<Map<String, Object>> series(@RequestParam(required = false) Long campaignId,
                                            @RequestParam(defaultValue = "30") int days) {
        return analytics.dailySeries(campaignId, days);
    }

    @GetMapping("/links")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public List<Map<String, Object>> links(@RequestParam(required = false) Long campaignId,
                                           @RequestParam(defaultValue = "30") int days,
                                           @RequestParam(defaultValue = "10") int limit) {
        return analytics.topLinks(campaignId, days, limit);
    }

    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> clients(@RequestParam(required = false) Long campaignId,
                                       @RequestParam(defaultValue = "30") int days) {
        return analytics.clients(campaignId, days);
    }

    @GetMapping("/campaigns")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public List<Map<String, Object>> campaigns(@RequestParam(defaultValue = "30") int days) {
        return analytics.byCampaign(days);
    }

    /** Why the headline moved, in the classifier's own words. */
    @GetMapping("/classifier")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> classifier(@RequestParam(required = false) Long campaignId,
                                          @RequestParam(defaultValue = "30") int days) {
        return analytics.classifierInfo(campaignId, days);
    }

    /** One call for the whole Analytics screen, so opening it is not five round trips. */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> overview(@RequestParam(required = false) Long campaignId,
                                        @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", analytics.summary(campaignId, days));
        out.put("series", analytics.dailySeries(campaignId, days));
        out.put("links", analytics.topLinks(campaignId, days, 10));
        out.put("clients", analytics.clients(campaignId, days));
        out.put("classifier", analytics.classifierInfo(campaignId, days));
        return out;
    }

    // ------------------------------------------------------------------
    // Engagement segments
    // ------------------------------------------------------------------

    /** Who clicked, who opened and did nothing, who cannot be known either way. */
    @GetMapping("/segments")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> segments(@RequestParam Long campaignId) {
        return segments.summary(campaignId);
    }

    /** The people behind one number, which is the whole point of showing the number. */
    @GetMapping("/segment/people")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public ResponseEntity<?> segmentPeople(@RequestParam Long campaignId,
                                           @RequestParam String segment,
                                           @RequestParam(defaultValue = "") String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        try {
            EngagementSegment target = EngagementSegment.parse(segment);
            var found = segmentRepository.listIn(campaignId, target.name(), q,
                    org.springframework.data.domain.PageRequest.of(
                            Math.max(0, page), Math.min(200, Math.max(1, size))));

            List<Map<String, Object>> rows = new ArrayList<>();
            for (var r : found.getContent()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("email", r.getEmail());
                row.put("name", r.getName());
                row.put("status", r.getStatus());
                row.put("opens", r.getOpenCount());
                row.put("clicks", r.getClickCount());
                row.put("sentAt", r.getSentAt() == null ? "" : r.getSentAt().toString());
                rows.add(row);
            }
            return ResponseEntity.ok(Map.of("rows", rows, "page", found.getNumber(),
                    "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements(),
                    "label", target.getLabel(), "description", target.getDescription()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/segment/export")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public void exportSegment(@RequestParam Long campaignId, @RequestParam String segment,
                              HttpServletResponse response) throws Exception {
        EngagementSegment target = EngagementSegment.parse(segment);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"campaign-" + campaignId
                + "-" + target.name().toLowerCase(java.util.Locale.ROOT) + ".csv\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("Email,Name,Group,Status,Opens,Clicks,Sent at");
            for (var r : segments.people(campaignId, target)) {
                writer.println(String.join(",",
                        csv(r.getEmail()), csv(r.getName()), csv(target.getLabel()), csv(r.getStatus()),
                        csv(String.valueOf(r.getOpenCount())), csv(String.valueOf(r.getClickCount())),
                        csv(r.getSentAt() == null ? "" : r.getSentAt().toString())));
            }
        }
    }

    /**
     * The action that makes analytics operational: a segment becomes an audience the
     * ordinary composer can mail. Needs LISTS_WRITE rather than ANALYTICS_READ, because
     * building an audience is a different kind of act from reading a report.
     */
    @PostMapping("/segment/save-as-list")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> saveSegmentAsList(@RequestParam Long campaignId,
                                               @RequestParam String segment,
                                               @RequestParam(required = false) String name) {
        try {
            Map<String, Object> result = segments.saveAsList(
                    campaignId, EngagementSegment.parse(segment), name);
            audit.record("SEGMENT_SAVED_AS_LIST", String.valueOf(result.get("name")),
                    result.get("added") + " people from campaign " + campaignId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** When this audience reads its mail. A real behaviour, and not a reading figure. */
    @GetMapping("/time-to-open")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> timeToOpen(@RequestParam Long campaignId) {
        return segments.timeToOpen(campaignId);
    }

    /**
     * Re-runs the classifier over stored history. Guarded on SETTINGS_WRITE rather than
     * ANALYTICS_READ because it rewrites numbers that have already been reported, and
     * audited for the same reason. A method level rule replaces any class level one, so
     * this endpoint deliberately does not also require ANALYTICS_READ.
     */
    @PostMapping("/reclassify")
    @PreAuthorize("hasAuthority('SETTINGS_WRITE')")
    public ResponseEntity<?> reclassify(@RequestParam(defaultValue = "50000") int max) {
        int changed = tracking.reclassify(max);
        audit.record("ANALYTICS_RECLASSIFIED", "tracking_event", changed + " verdict(s) rewritten");
        return ResponseEntity.ok(Map.of(
                "message", changed + " event(s) reclassified.",
                "changed", changed));
    }
}
