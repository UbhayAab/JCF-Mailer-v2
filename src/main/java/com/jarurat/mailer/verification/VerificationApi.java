package com.jarurat.mailer.verification;

import com.jarurat.mailer.models.MailingList;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.repositories.SubscriberRepository;
import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything here is gated on VERIFICATION_RUN. Even the read endpoints: a list
 * hygiene report is a map of who we hold addresses for, and the roles that have
 * no business seeing the marketing base have no business seeing this either.
 */
@RestController
@RequestMapping("/api/verification")
public class VerificationApi {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM, HH:mm");

    private final VerificationService verification;
    private final VerificationResultRepository results;
    private final SubscriberRepository subscribers;
    private final MailingListRepository lists;
    private final AuditService audit;

    public VerificationApi(VerificationService verification,
                           VerificationResultRepository results,
                           SubscriberRepository subscribers,
                           MailingListRepository lists,
                           AuditService audit) {
        this.verification = verification;
        this.results = results;
        this.subscribers = subscribers;
        this.lists = lists;
        this.audit = audit;
    }

    // ---------------- reporting ----------------

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public Map<String, Object> summary(@RequestParam(required = false) Long listId) {
        Map<String, Object> out = new LinkedHashMap<>(verification.summary(listId));
        if (listId != null) {
            out.put("listName", lists.findById(listId).map(MailingList::getName).orElse(""));
        }
        out.put("settings", verification.settings());
        return out;
    }

    @GetMapping("/results")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public Map<String, Object> results(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "") String verdict,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        Page<VerificationResult> found = results.search(q.trim(), verdict.trim().toUpperCase(),
                PageRequest.of(Math.max(0, page), Math.min(500, Math.max(1, size))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (VerificationResult row : found) {
            Map<String, Object> m = row.toCheck().toRow();
            m.put("checkedAt", row.getCheckedAt() == null ? "" : row.getCheckedAt().format(STAMP));
            m.put("checkedBy", row.getCheckedBy() == null ? "" : row.getCheckedBy());
            rows.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("page", found.getNumber());
        out.put("totalPages", found.getTotalPages());
        out.put("total", found.getTotalElements());
        return out;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public Map<String, Object> settings() {
        return verification.settings();
    }

    // ---------------- running ----------------

    @PostMapping("/list")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public ResponseEntity<?> verifyList(@RequestParam Long listId,
                                        @RequestParam(defaultValue = "false") boolean force) {
        try {
            VerificationService.Run run = verification.startListRun(listId, force);
            return ResponseEntity.ok(Map.of(
                    "key", run.key,
                    "total", run.total,
                    "message", "Verifying " + run.total + " addresses on \"" + run.label + "\"."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * Accepts a pasted blob, one address per line or comma separated, and answers
     * synchronously. Capped by verification.maxAdhoc so nobody parks a browser
     * request on a ten thousand address paste.
     */
    @PostMapping("/addresses")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public ResponseEntity<?> verifyAddresses(@RequestParam String emails,
                                             @RequestParam(defaultValue = "false") boolean force) {
        List<String> parsed = Arrays.stream(emails.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (parsed.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Paste at least one address."));

        try {
            List<EmailCheck> checks = verification.verifyAddresses(parsed, force);
            List<Map<String, Object>> rows = new ArrayList<>(checks.size());
            for (EmailCheck check : checks) rows.add(check.toRow());

            long deliverable = checks.stream().filter(c -> c.verdict() == Verdict.DELIVERABLE).count();
            long risky = checks.stream().filter(c -> c.verdict() == Verdict.RISKY).count();
            long undeliverable = checks.stream().filter(c -> c.verdict() == Verdict.UNDELIVERABLE).count();

            audit.record("VERIFICATION_ADHOC", parsed.size() + " addresses",
                    deliverable + " deliverable, " + risky + " risky, " + undeliverable + " undeliverable");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("checked", checks.size());
            out.put("deliverable", deliverable);
            out.put("risky", risky);
            out.put("undeliverable", undeliverable);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/progress")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public Map<String, Object> progress(@RequestParam String key) {
        return verification.progress(key);
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public Map<String, Object> cancel(@RequestParam String key) {
        boolean stopped = verification.cancel(key);
        if (stopped) audit.record("VERIFICATION_CANCELLED", key, null);
        return Map.of("cancelled", stopped);
    }

    // ---------------- export ----------------

    /**
     * The "Export clean list" button. Runs the same gate the dispatcher would,
     * so what comes out of here is exactly what a send would have kept.
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('VERIFICATION_RUN')")
    public void exportClean(@RequestParam Long listId, HttpServletResponse response) throws Exception {
        MailingList list = lists.findById(listId).orElse(null);
        if (list == null) {
            response.sendError(400, "No such list.");
            return;
        }

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"verified-clean-" + listId + ".csv\"");

        PrintWriter writer = response.getWriter();
        writer.println("Email,First Name,Last Name");

        int page = 0;
        int kept = 0;
        while (true) {
            Page<Subscriber> chunk = subscribers.search("", "", listId,
                    PageRequest.of(page, 500, Sort.by("id")));
            if (chunk.isEmpty()) break;

            List<String> emails = chunk.getContent().stream()
                    .map(Subscriber::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .toList();
            Set<String> safe = verification.safeToSend(emails);

            for (Subscriber s : chunk) {
                if (s.getEmail() == null || !safe.contains(s.getEmail().toLowerCase())) continue;
                writer.println(String.join(",",
                        csv(s.getEmail()), csv(s.getFirstName()), csv(s.getLastName())));
                kept++;
            }

            if (!chunk.hasNext()) break;
            page++;
        }

        audit.record("VERIFICATION_EXPORTED", list.getName(), kept + " safe addresses");
    }

    /**
     * Quoting alone is not enough here. A subscriber-supplied name beginning with
     * =, +, -, @, tab or carriage return is treated as a formula by Excel, Sheets
     * and LibreOffice, and one of those formulas can exfiltrate the rest of the
     * sheet the moment someone opens this export. The leading apostrophe is what
     * those applications read as "this is text", and it is stripped on display.
     */
    private static String csv(String value) {
        if (value == null) return "";
        String clean = value.replace("\"", "\"\"");
        if (!clean.isEmpty() && "=+-@\t\r".indexOf(clean.charAt(0)) >= 0) {
            clean = "'" + clean;
        }
        return clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")
                ? "\"" + clean + "\"" : clean;
    }
}
