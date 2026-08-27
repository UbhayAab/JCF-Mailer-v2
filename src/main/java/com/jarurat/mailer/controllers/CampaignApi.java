package com.jarurat.mailer.controllers;

import com.jarurat.mailer.merge.MergeTags;
import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.models.CampaignRecipient;
import com.jarurat.mailer.repositories.CampaignRecipientRepository;
import com.jarurat.mailer.repositories.CampaignRepository;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.services.CampaignService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.jarurat.mailer.controllers.AudienceApi.csv;
import static com.jarurat.mailer.controllers.OverviewApi.STAMP;
import static com.jarurat.mailer.controllers.OverviewApi.nz;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignApi {

    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final CampaignService service;
    private final AuditService audit;

    public CampaignApi(CampaignRepository campaigns, CampaignRecipientRepository recipients,
                       CampaignService service, AuditService audit) {
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Campaign c : campaigns.findAllByOrderByCreatedAtDesc()) out.add(service.stats(c));
        return out;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        Campaign campaign = campaigns.findById(id).orElse(null);
        if (campaign == null) return ResponseEntity.badRequest().body(Map.of("error", "No such campaign."));

        Map<String, Object> body = new LinkedHashMap<>(service.stats(campaign));
        body.put("htmlBody", nz(campaign.getHtmlBody()));
        body.put("preheader", nz(campaign.getPreheader()));
        body.put("fromName", nz(campaign.getFromName()));
        body.put("replyTo", nz(campaign.getReplyTo()));
        body.put("trackOpens", campaign.isTrackOpens());
        body.put("trackClicks", campaign.isTrackClicks());
        body.put("links", service.linkBreakdown(id));
        if (campaign.getListId() != null) body.put("mailable", service.mailableSize(campaign.getListId()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/save")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> save(@RequestParam(required = false) Long id,
                                  @RequestParam String name,
                                  @RequestParam(required = false) String subject,
                                  @RequestParam(required = false) String htmlBody,
                                  @RequestParam(required = false) String preheader,
                                  @RequestParam(required = false) String fromName,
                                  @RequestParam(required = false) String replyTo,
                                  @RequestParam(required = false) Long listId,
                                  @RequestParam(defaultValue = "true") boolean trackOpens,
                                  @RequestParam(defaultValue = "true") boolean trackClicks) {
        String clean = name.trim();
        if (clean.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Give the campaign a name."));

        Campaign campaign;
        if (id != null) {
            campaign = campaigns.findById(id).orElse(null);
            if (campaign == null) return ResponseEntity.badRequest().body(Map.of("error", "No such campaign."));
            if (!campaign.isEditable())
                return ResponseEntity.badRequest().body(Map.of("error",
                        "A campaign that is sending or already sent cannot be edited."));
            if (!campaign.getName().equals(clean) && campaigns.existsByName(clean))
                return ResponseEntity.badRequest().body(Map.of("error", "That name is taken."));
            campaign.setName(clean);
        } else {
            if (campaigns.existsByName(clean))
                return ResponseEntity.badRequest().body(Map.of("error", "A campaign called \"" + clean + "\" exists."));
            campaign = new Campaign(clean, AuditService.currentActor());
        }

        campaign.setSubject(subject);
        campaign.setHtmlBody(htmlBody);
        campaign.setPreheader(preheader);
        campaign.setFromName(fromName);
        campaign.setReplyTo(replyTo);
        campaign.setListId(listId);
        campaign.setTrackOpens(trackOpens);
        campaign.setTrackClicks(trackClicks);
        campaign = campaigns.save(campaign);

        audit.record(id == null ? "CAMPAIGN_CREATED" : "CAMPAIGN_UPDATED", clean, null);
        return ResponseEntity.ok(Map.of("id", campaign.getId(), "message", "Campaign saved."));
    }

    @PostMapping("/send")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> send(@RequestParam Long id) {
        try {
            CampaignService.Progress progress = service.send(id);
            audit.record("CAMPAIGN_SENT", service.require(id).getName(),
                    progress.total + " recipients");
            return ResponseEntity.ok(Map.of("message", "Sending to " + progress.total + " recipients.",
                    "total", progress.total));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/schedule")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> schedule(@RequestParam Long id, @RequestParam String when) {
        try {
            LocalDateTime at = LocalDateTime.parse(when);
            service.schedule(id, at);
            audit.record("CAMPAIGN_SCHEDULED", service.require(id).getName(), at.toString());
            return ResponseEntity.ok(Map.of("message", "Scheduled for " + at.format(STAMP) + "."));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read that date and time."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/cancel-schedule")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> cancelSchedule(@RequestParam Long id) {
        try {
            service.cancelSchedule(id);
            audit.record("CAMPAIGN_SCHEDULE_CANCELLED", service.require(id).getName(), null);
            return ResponseEntity.ok(Map.of("message", "Schedule cancelled, back to draft."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * The merge tags a piece of creative uses, with a sample value for each.
     *
     * Takes the raw text rather than a campaign id on purpose: the composer needs
     * this while the user is still typing, before anything is saved, and a version
     * that could only read the saved row would always be one edit behind. Pass an
     * id instead to ask about a campaign already on disk.
     */
    @PostMapping("/merge-tags")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<?> mergeTags(@RequestParam(required = false) Long id,
                                       @RequestParam(required = false) String subject,
                                       @RequestParam(required = false) String preheader,
                                       @RequestParam(required = false) String htmlBody,
                                       @RequestParam(required = false) String testAddress) {
        try {
            List<String> tags = id != null
                    ? service.mergeTagsOf(id)
                    : MergeTags.extract(subject, preheader, htmlBody);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String tag : tags) {
                rows.add(Map.of("tag", tag, "sample", MergeTags.sampleFor(tag, testAddress)));
            }
            return ResponseEntity.ok(Map.of("tags", rows, "reserved", MergeTags.RESERVED));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Merge values arrive as merge.NAME=..., merge.CITY=... because the console
     * posts form-encoded bodies with the CSRF header every other call uses, and a
     * variable-length map has to survive that encoding.
     */
    @PostMapping("/test-send")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> testSend(@RequestParam Long id, @RequestParam String to,
                                      @RequestParam Map<String, String> allParams) {
        try {
            Map<String, String> merge = MergeTags.fromParams(allParams, "merge.");
            String messageId = service.sendTest(id, to, merge);
            audit.record("CAMPAIGN_TEST_SENT", service.require(id).getName(),
                    merge.isEmpty() ? to : to + " with " + merge.size() + " merge value(s)");
            return ResponseEntity.ok(Map.of("message", "Test sent to " + to + ".", "messageId", messageId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "SES rejected it: " + e.getMessage()));
        }
    }

    @PostMapping("/queue")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> queue(@RequestParam Long id) {
        try {
            int queued = service.queueAudience(id);
            return ResponseEntity.ok(Map.of("message", queued + " recipient(s) queued.", "queued", queued));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/requeue-failed")
    @PreAuthorize("hasAuthority('CAMPAIGNS_SEND')")
    public ResponseEntity<?> requeueFailed(@RequestParam Long id) {
        int n = service.requeueFailed(id);
        return ResponseEntity.ok(Map.of("message", n + " failed recipient(s) moved back to the queue."));
    }

    @PostMapping("/delete")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> delete(@RequestParam Long id) {
        Campaign campaign = campaigns.findById(id).orElse(null);
        if (campaign == null) return ResponseEntity.badRequest().body(Map.of("error", "No such campaign."));
        if ("SENDING".equals(campaign.getStatus()))
            return ResponseEntity.badRequest().body(Map.of("error", "Wait for the send to finish first."));

        recipients.deleteByCampaignId(id);
        campaigns.deleteById(id);
        audit.record("CAMPAIGN_DELETED", campaign.getName(), null);
        return ResponseEntity.ok(Map.of("message", "Campaign deleted. Subscribers and suppressions are untouched."));
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public Map<String, Object> progress(@PathVariable Long id) {
        CampaignService.Progress p = service.getProgress(id);
        Map<String, Object> out = new LinkedHashMap<>();
        if (p == null) { out.put("active", false); return out; }
        out.put("active", "SENDING".equals(p.status));
        out.put("status", p.status);
        out.put("total", p.total);
        out.put("sent", p.sent.get());
        out.put("failed", p.failed.get());
        out.put("skipped", p.skipped.get());
        out.put("elapsedMs", System.currentTimeMillis() - p.startedAt);
        out.put("lastError", nz(p.lastError));
        return out;
    }

    @GetMapping("/{id}/recipients")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public Map<String, Object> recipients(@PathVariable Long id,
                                          @RequestParam(defaultValue = "") String status,
                                          @RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Page<CampaignRecipient> found = recipients.search(id, status, q,
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "id")));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (CampaignRecipient r : found.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", r.getEmail());
            m.put("name", nz(r.getName()));
            m.put("status", r.getStatus());
            m.put("sentAt", r.getSentAt() == null ? "" : r.getSentAt().format(STAMP));
            m.put("openCount", r.getOpenCount());
            m.put("clickCount", r.getClickCount());
            m.put("failReason", nz(r.getFailReason()));
            rows.add(m);
        }
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }

    /**
     * The exact HTML SES will be handed, tracking injection included. Takes the
     * same merge.* values the test send takes, so "On the wire" shows the message
     * the tester just received rather than a different one built from samples.
     */
    @GetMapping(value = "/{id}/rendered", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public ResponseEntity<String> rendered(@PathVariable Long id,
                                           @RequestParam Map<String, String> allParams) {
        try {
            return ResponseEntity.ok(service.previewRendered(id, MergeTags.fromParams(allParams, "merge.")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(String.valueOf(e.getMessage()));
        }
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    public void export(@PathVariable Long id,
                       @RequestParam(defaultValue = "") String status,
                       HttpServletResponse response) throws Exception {
        Campaign campaign = campaigns.findById(id).orElseThrow();
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\""
                + campaign.getName().replaceAll("[^A-Za-z0-9_-]", "_") + ".csv\"");

        PrintWriter writer = response.getWriter();
        writer.println("Email,Name,Status,Sent At,Opens,Clicks,Failure");

        int page = 0;
        while (true) {
            Page<CampaignRecipient> chunk = recipients.search(id, status, "", PageRequest.of(page, 500));
            if (chunk.isEmpty()) break;
            for (CampaignRecipient r : chunk) {
                writer.println(String.join(",",
                        csv(r.getEmail()), csv(r.getName()), csv(r.getStatus()),
                        csv(r.getSentAt() == null ? "" : r.getSentAt().toString()),
                        String.valueOf(r.getOpenCount()), String.valueOf(r.getClickCount()),
                        csv(r.getFailReason())));
            }
            if (!chunk.hasNext()) break;
            page++;
        }
    }
}
