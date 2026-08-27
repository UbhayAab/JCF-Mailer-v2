package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.TransactionalLog;
import com.jarurat.mailer.repositories.TransactionalLogRepository;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.services.TransactionalMailService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.jarurat.mailer.controllers.OverviewApi.STAMP;
import static com.jarurat.mailer.controllers.OverviewApi.nz;

/** Console side of transactional mail. The machine API lives in PublicApiV1. */
@RestController
@RequestMapping("/api/transactional")
public class TransactionalApi {

    private final TransactionalLogRepository logs;
    private final TransactionalMailService service;
    private final AuditService audit;

    public TransactionalApi(TransactionalLogRepository logs, TransactionalMailService service,
                            AuditService audit) {
        this.logs = logs;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/log")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_READ')")
    public Map<String, Object> log(@RequestParam(defaultValue = "") String q,
                                   @RequestParam(defaultValue = "") String status,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        Page<TransactionalLog> found = logs.search(q, status,
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (TransactionalLog l : found.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("template", nz(l.getTemplateSlug()));
            m.put("to", nz(l.getToEmail()));
            m.put("subject", nz(l.getSubject()));
            m.put("status", nz(l.getStatus()));
            m.put("error", nz(l.getError()));
            m.put("sentVia", nz(l.getSentVia()));
            m.put("at", l.getTimestamp() == null ? "" : l.getTimestamp().format(STAMP));
            rows.add(m);
        }
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }

    /** Manual send from the console, for when HR wants to fire one off by hand. */
    @PostMapping("/send")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_SEND')")
    public ResponseEntity<?> send(@RequestParam String slug,
                                  @RequestParam String to,
                                  @RequestParam(required = false) String subject,
                                  @RequestParam Map<String, String> allParams) {
        Map<String, String> merge = new HashMap<>(allParams);
        merge.remove("slug");
        merge.remove("to");
        merge.remove("subject");
        merge.remove("_csrf");

        var result = service.send(slug, to, merge, subject, AuditService.currentActor());
        audit.record("TRANSACTIONAL_SENT", to, "template " + slug
                + (result.sent() ? "" : " FAILED: " + result.error()));

        return result.sent()
                ? ResponseEntity.ok(Map.of("message", "Sent to " + to + ".", "messageId", result.messageId()))
                : ResponseEntity.badRequest().body(Map.of("error", String.valueOf(result.error())));
    }
}
