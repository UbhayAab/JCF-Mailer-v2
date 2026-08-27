package com.jarurat.mailer.controllers;

import com.jarurat.mailer.merge.MergeTags;
import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.services.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import static com.jarurat.mailer.controllers.OverviewApi.STAMP;
import static com.jarurat.mailer.controllers.OverviewApi.nz;

@RestController
@RequestMapping("/api/templates")
public class TemplateApi {


    private final EmailTemplateRepository templates;
    private final AuditService audit;

    public TemplateApi(EmailTemplateRepository templates, AuditService audit) {
        this.templates = templates;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TEMPLATES_READ')")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String type) {
        List<EmailTemplate> found = type.isBlank()
                ? templates.findAllByOrderByCreatedAtDesc()
                : templates.findByTypeOrderByCreatedAtDesc(type);

        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailTemplate t : found) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            m.put("subject", nz(t.getSubject()));
            m.put("htmlBody", nz(t.getHtmlBody()));
            m.put("type", t.getType());
            m.put("description", nz(t.getDescription()));
            m.put("mergeTags", mergeTagsOf(t));
            m.put("createdAt", t.getCreatedAt() == null ? "" : t.getCreatedAt().format(STAMP));
            m.put("createdBy", nz(t.getCreatedBy()));
            out.add(m);
        }
        return out;
    }

    /** Lists the placeholders a caller has to supply, so the API is self documenting. */
    private List<String> mergeTagsOf(EmailTemplate t) {
        return MergeTags.extract(t.getSubject(), t.getHtmlBody());
    }

    @PostMapping("/save")
    @PreAuthorize("hasAuthority('TEMPLATES_WRITE')")
    public ResponseEntity<?> save(@RequestParam(required = false) Long id,
                                  @RequestParam String name,
                                  @RequestParam(required = false) String slug,
                                  @RequestParam(required = false) String subject,
                                  @RequestParam(required = false) String htmlBody,
                                  @RequestParam(defaultValue = "MARKETING") String type,
                                  @RequestParam(required = false) String description) {
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));

        String finalSlug = (slug == null || slug.isBlank())
                ? EmailTemplate.slugify(name) : EmailTemplate.slugify(slug);

        EmailTemplate template;
        if (id != null) {
            template = templates.findById(id).orElse(null);
            if (template == null) return ResponseEntity.badRequest().body(Map.of("error", "No such template."));
            if (!template.getSlug().equals(finalSlug) && templates.existsBySlug(finalSlug))
                return ResponseEntity.badRequest().body(Map.of("error", "Slug \"" + finalSlug + "\" is taken."));
            template.setName(name.trim());
            template.setSlug(finalSlug);
            template.setSubject(subject);
            template.setHtmlBody(htmlBody);
            template.setType(type);
            template.setDescription(description);
            template.setUpdatedAt(LocalDateTime.now());
        } else {
            if (templates.existsBySlug(finalSlug))
                return ResponseEntity.badRequest().body(Map.of("error", "Slug \"" + finalSlug + "\" is taken."));
            template = new EmailTemplate(name.trim(), finalSlug, subject, htmlBody, type,
                    AuditService.currentActor());
            template.setDescription(description);
        }

        template = templates.save(template);
        audit.record(id == null ? "TEMPLATE_CREATED" : "TEMPLATE_UPDATED", template.getSlug(), null);
        return ResponseEntity.ok(Map.of("id", template.getId(), "slug", template.getSlug(),
                "message", "Template saved."));
    }

    @PostMapping("/delete")
    @PreAuthorize("hasAuthority('TEMPLATES_WRITE')")
    public ResponseEntity<?> delete(@RequestParam Long id) {
        EmailTemplate template = templates.findById(id).orElse(null);
        if (template == null) return ResponseEntity.badRequest().body(Map.of("error", "No such template."));
        templates.deleteById(id);
        audit.record("TEMPLATE_DELETED", template.getSlug(), null);
        return ResponseEntity.ok(Map.of("message", "Template deleted."));
    }
}
