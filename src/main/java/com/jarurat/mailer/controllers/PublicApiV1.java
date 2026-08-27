package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.services.TransactionalMailService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Machine-facing API, authenticated by API key. This is the surface the HR
 * system calls when an interview is scheduled.
 *
 *   curl -X POST https://mailer.jarurat.care/api/v1/transactional/send \
 *     -H "Authorization: Bearer jcf_live_..." \
 *     -H "Content-Type: application/json" \
 *     -d '{"template":"interview-round-1",
 *          "to":"candidate@example.com",
 *          "data":{"CANDIDATE_NAME":"Priya","ROLE":"Program Manager",
 *                  "INTERVIEW_DATE":"21 Aug 2026","INTERVIEW_TIME":"11:00 IST",
 *                  "INTERVIEW_MODE":"Google Meet","INTERVIEWER":"Kishan",
 *                  "SENDER_NAME":"People Team"}}'
 */
@RestController
@RequestMapping("/api/v1")
public class PublicApiV1 {

    private final TransactionalMailService transactional;
    private final EmailTemplateRepository templates;

    public PublicApiV1(TransactionalMailService transactional, EmailTemplateRepository templates) {
        this.transactional = transactional;
        this.templates = templates;
    }

    public record SendRequest(String template, String to, String subject, Map<String, String> data) {}

    @PostMapping("/transactional/send")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_SEND')")
    public ResponseEntity<?> send(@RequestBody SendRequest body, HttpServletRequest request) {
        if (body == null || body.template() == null || body.template().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "\"template\" is required."));
        if (body.to() == null || body.to().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "\"to\" is required."));

        String via = "apikey:" + Objects.toString(request.getAttribute("apiKeyName"), "unknown");
        var result = transactional.send(body.template(), body.to(),
                body.data() == null ? Map.of() : body.data(), body.subject(), via);

        return result.sent()
                ? ResponseEntity.ok(Map.of("status", "sent", "messageId", result.messageId()))
                : ResponseEntity.badRequest().body(Map.of("status", "failed", "error", String.valueOf(result.error())));
    }

    /** Lets the calling system discover which placeholders a template expects. */
    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_READ')")
    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailTemplate t : templates.findByTypeOrderByCreatedAtDesc("TRANSACTIONAL")) {
            out.add(Map.of(
                    "slug", t.getSlug(),
                    "name", t.getName(),
                    "subject", t.getSubject() == null ? "" : t.getSubject()));
        }
        return out;
    }

    @GetMapping("/ping")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_READ')")
    public Map<String, Object> ping(HttpServletRequest request) {
        return Map.of("status", "ok",
                "authenticatedAs", Objects.toString(request.getAttribute("apiKeyName"), "unknown"));
    }
}
