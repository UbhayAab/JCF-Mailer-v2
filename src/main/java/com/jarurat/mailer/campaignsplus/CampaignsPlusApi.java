package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.models.Campaign;
import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.services.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Import, template and pre-send endpoints. Everything here is additive: the
 * existing campaign and audience APIs keep working untouched.
 */
@RestController
@RequestMapping("/api/campaignsplus")
public class CampaignsPlusApi {

    private final CsvImportService importer;
    private final TemplateLibraryService templateLibrary;
    private final SafetyCheckService safety;
    private final AuditService audit;
    private final AudienceMatchService matcher;
    private final com.jarurat.mailer.repositories.MailingListRepository lists;

    public CampaignsPlusApi(CsvImportService importer,
                            TemplateLibraryService templateLibrary,
                            SafetyCheckService safety,
                            AuditService audit,
                            AudienceMatchService matcher,
                            com.jarurat.mailer.repositories.MailingListRepository lists) {
        this.matcher = matcher;
        this.lists = lists;
        this.importer = importer;
        this.templateLibrary = templateLibrary;
        this.safety = safety;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /** What the importer understands, so the upload screen can say so up front. */
    @GetMapping("/import/columns")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_READ')")
    public Map<String, Object> columns() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("understood", importer.understoodColumns());
        out.put("maxBytes", importer.getMaxBytes());
        out.put("maxRows", importer.getMaxRows());
        out.put("note", "Column order does not matter. Quoted commas, BOM, CRLF, semicolon and tab "
                + "separated files are all handled.");
        return out;
    }

    /**
     * dryRun reports exactly what would happen without writing a row, which is
     * the only safe way to check a file you did not produce yourself.
     *
     * map.0=email, map.3=company and so on override the detected column layout.
     * Anything not named is ignored, which is how a column gets left out.
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_WRITE')")
    public ResponseEntity<?> importCsv(@RequestParam("file") MultipartFile file,
                                       @RequestParam(required = false) Long listId,
                                       @RequestParam(required = false) String createListName,
                                       @RequestParam(required = false) String source,
                                       @RequestParam(defaultValue = "false") boolean dryRun,
                                       @RequestParam(defaultValue = "500") int maxIssues,
                                       @RequestParam Map<String, String> allParams) {
        try {
            Long targetList = listId;
            if (targetList == null && createListName != null && !createListName.isBlank() && !dryRun) {
                targetList = lists.save(new com.jarurat.mailer.models.MailingList(
                        createListName.trim(), "Created from a composer import", "IMPORT",
                        AuditService.currentActor())).getId();
            }

            ImportReport report = importer.importFile(file, targetList, source, dryRun, maxIssues,
                    columnMapping(allParams));
            if (!dryRun) {
                audit.record("CSV_IMPORTED", report.listName() == null ? "(no list)" : report.listName(),
                        report.created() + " created, " + report.addedToList() + " added, "
                        + report.skippedSuppressed() + " suppressed, " + report.skippedDuplicate()
                        + " duplicate, " + report.invalid() + " invalid");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("report", report);
            body.put("listId", targetList);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Could not read that file: " + e.getMessage()));
        }
    }

    /** What the file looks like, from its first rows only, so a mapping table can be shown. */
    @PostMapping("/import/profile")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_READ')")
    public ResponseEntity<?> profile(@RequestParam("file") MultipartFile file,
                                     @RequestParam(defaultValue = "25") int sampleRows) {
        try {
            return ResponseEntity.ok(importer.profile(file, sampleRows));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Could not read that file: " + e.getMessage()));
        }
    }

    /**
     * Everything the review screen needs in one upload: what the file looks like,
     * exactly what the import would do, and where the creative's merge tags do not
     * line up with the data.
     *
     * One call rather than three because each one would have to re-upload the same
     * file, and a 20MB list uploaded three times to answer one question is the kind
     * of thing that makes people skip the review.
     */
    @PostMapping("/import/review")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_WRITE')")
    public ResponseEntity<?> review(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) Long listId,
                                    @RequestParam(required = false) String subject,
                                    @RequestParam(required = false) String preheader,
                                    @RequestParam(required = false) String htmlBody,
                                    @RequestParam(defaultValue = "25") int sampleRows,
                                    @RequestParam Map<String, String> allParams) {
        try {
            Map<Integer, String> mapping = columnMapping(allParams);
            ImportProfile profile = importer.profile(file, sampleRows);
            if (mapping.isEmpty()) mapping = detectedMapping(profile);

            ImportReport dry = importer.importFile(file, listId, null, true, 200, mapping);
            List<String> tags = com.jarurat.mailer.merge.MergeTags.extract(subject, preheader, htmlBody);
            List<AudienceMatchService.Discrepancy> findings =
                    matcher.reconcile(tags, profile, mapping, dry);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("profile", profile);
            body.put("report", dry);
            body.put("mapping", mapping);
            body.put("mergeTags", tags);
            body.put("discrepancies", findings);
            body.put("blocked", AudienceMatchService.blocked(findings));
            body.put("targetFields", ImportProfile.TARGET_FIELDS);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Could not read that file: " + e.getMessage()));
        }
    }

    /** map.3=company -> {3: "company"}. Silently drops anything malformed. */
    private static Map<Integer, String> columnMapping(Map<String, String> params) {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        if (params == null) return mapping;
        params.forEach((key, value) -> {
            if (key == null || !key.startsWith("map.") || value == null || value.isBlank()) return;
            if (!ImportProfile.TARGET_FIELDS.contains(value)) return;
            try {
                int index = Integer.parseInt(key.substring(4).trim());
                if (index >= 0 && index < 200) mapping.put(index, value);
            } catch (NumberFormatException ignored) { }
        });
        return mapping;
    }

    /** The importer's own guess, as the starting point for the review screen. */
    private static Map<Integer, String> detectedMapping(ImportProfile profile) {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        for (ImportProfile.Column column : profile.columns()) {
            if (column.detectedField() != null) mapping.put(column.index(), column.detectedField());
        }
        return mapping;
    }

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('TEMPLATES_READ')")
    public List<Map<String, Object>> library(@RequestParam(defaultValue = "") String type) {
        return templateLibrary.library(type);
    }

    /*
     * subscriberId names a row in the subscriber table and the rendered preview
     * echoes that person's name and address back, so it is a read of the audience
     * however it is dressed up. TEMPLATES_READ alone is not enough: HR holds it and
     * was deliberately denied SUBSCRIBERS_READ, and without this second clause the
     * caller could walk the whole base one id at a time.
     */
    private static final String PREVIEW_GUARD =
            "hasAuthority('TEMPLATES_READ') and (#subscriberId == null or hasAuthority('SUBSCRIBERS_READ'))";

    private static final String COMPOSER_GUARD =
            "hasAnyAuthority('TEMPLATES_READ','CAMPAIGNS_WRITE') "
            + "and (#subscriberId == null or hasAuthority('SUBSCRIBERS_READ'))";

    @GetMapping("/templates/{id}/preview")
    @PreAuthorize(PREVIEW_GUARD)
    public ResponseEntity<?> previewTemplate(@PathVariable Long id,
                                             @RequestParam(required = false) Long subscriberId) {
        try {
            return ResponseEntity.ok(templateLibrary.previewTemplate(id, subscriberId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /*
     * There was a /templates/{id}/rendered here that returned the stored template
     * body as text/html from our own origin. Template bodies are author supplied
     * and the console CSP allows inline script, so a MARKETER could have stored a
     * script that ran with an OWNER's session the moment they opened that URL.
     * Nothing consumed it: the console previews campaigns through
     * /api/campaigns/{id}/rendered inside a sandboxed iframe. Deleted rather than
     * patched, because the safe version is the one that already exists.
     */

    /** Validates copy that has not been saved yet, straight out of the composer. */
    @PostMapping("/templates/validate")
    @PreAuthorize(COMPOSER_GUARD)
    public TemplateLibraryService.Validation validate(@RequestParam(required = false) String subject,
                                                      @RequestParam(required = false) String htmlBody,
                                                      @RequestParam(defaultValue = "MARKETING") String type,
                                                      @RequestParam(required = false) String preheader,
                                                      @RequestParam(required = false) Long subscriberId,
                                                      @RequestParam(defaultValue = "true") boolean trackOpens,
                                                      @RequestParam(defaultValue = "true") boolean trackClicks) {
        return templateLibrary.validate(subject, htmlBody, type, preheader,
                templateLibrary.sample(subscriberId), trackOpens, trackClicks);
    }

    @PostMapping("/templates/preview")
    @PreAuthorize(COMPOSER_GUARD)
    public TemplateLibraryService.Preview previewRaw(@RequestParam(required = false) String subject,
                                                     @RequestParam(required = false) String htmlBody,
                                                     @RequestParam(defaultValue = "MARKETING") String type,
                                                     @RequestParam(required = false) String preheader,
                                                     @RequestParam(required = false) Long subscriberId,
                                                     @RequestParam(defaultValue = "true") boolean trackOpens,
                                                     @RequestParam(defaultValue = "true") boolean trackClicks) {
        return templateLibrary.previewRaw(subject, htmlBody, type, preheader, subscriberId,
                trackOpens, trackClicks);
    }

    @PostMapping("/templates/apply")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> apply(@RequestParam Long templateId,
                                   @RequestParam Long campaignId,
                                   @RequestParam(defaultValue = "true") boolean overwriteSubject) {
        try {
            Campaign campaign = templateLibrary.applyToCampaign(templateId, campaignId, overwriteSubject);
            audit.record("TEMPLATE_APPLIED", campaign.getName(), "templateId " + templateId);
            return ResponseEntity.ok(Map.of("message", "Template loaded into \"" + campaign.getName() + "\"."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/templates/save-from-campaign")
    @PreAuthorize("hasAuthority('TEMPLATES_WRITE')")
    public ResponseEntity<?> saveFromCampaign(@RequestParam Long campaignId,
                                              @RequestParam String name,
                                              @RequestParam(required = false) String description) {
        try {
            EmailTemplate saved = templateLibrary.saveCampaignAsTemplate(campaignId, name, description);
            audit.record("TEMPLATE_CREATED", saved.getSlug(), "saved from campaign " + campaignId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "slug", saved.getSlug(),
                    "message", "Saved to the template library."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------
    // Pre-send
    // ------------------------------------------------------------------

    @GetMapping("/campaigns/{id}/preview")
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ') and (#subscriberId == null or hasAuthority('SUBSCRIBERS_READ'))")
    public ResponseEntity<?> previewCampaign(@PathVariable Long id,
                                             @RequestParam(required = false) Long subscriberId) {
        try {
            return ResponseEntity.ok(templateLibrary.previewCampaign(id, subscriberId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Read only. The same checks run again inside the send path, which is authoritative. */
    @GetMapping("/campaigns/{id}/safety-check")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> safetyCheck(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(safety.check(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * The only way to clear a SUPPRESSED_QUEUED block. Re-queueing leaves those
     * rows alone, so without this the campaign stays blocked for good.
     */
    @PostMapping("/campaigns/{id}/drop-suppressed")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    public ResponseEntity<?> dropSuppressed(@PathVariable Long id) {
        try {
            int dropped = safety.dropSuppressedQueued(id);
            audit.record("SUPPRESSED_QUEUE_CLEARED", "campaign " + id, dropped + " recipient(s) skipped");
            return ResponseEntity.ok(Map.of("dropped", dropped,
                    "message", dropped + " suppressed recipient(s) removed from the queue."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
