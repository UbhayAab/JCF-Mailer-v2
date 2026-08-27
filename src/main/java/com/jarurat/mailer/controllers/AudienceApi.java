package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.GlobalSuppression;
import com.jarurat.mailer.models.MailingList;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.*;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.services.SubscriberService;
import com.jarurat.mailer.services.SuppressionService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.PrintWriter;
import java.util.*;

import static com.jarurat.mailer.controllers.OverviewApi.STAMP;
import static com.jarurat.mailer.controllers.OverviewApi.nz;

@RestController
@RequestMapping("/api")
public class AudienceApi {

    private final SubscriberRepository subscribers;
    private final MailingListRepository lists;
    private final ListMemberRepository members;
    private final GlobalSuppressionRepository suppressionRepo;
    private final SubscriberService subscriberService;
    private final SuppressionService suppression;
    private final AuditService audit;

    public AudienceApi(SubscriberRepository subscribers, MailingListRepository lists,
                       ListMemberRepository members, GlobalSuppressionRepository suppressionRepo,
                       SubscriberService subscriberService, SuppressionService suppression,
                       AuditService audit) {
        this.subscribers = subscribers;
        this.lists = lists;
        this.members = members;
        this.suppressionRepo = suppressionRepo;
        this.subscriberService = subscriberService;
        this.suppression = suppression;
        this.audit = audit;
    }

    // ---------------- lists ----------------

    @GetMapping("/lists")
    @PreAuthorize("hasAuthority('LISTS_READ')")
    public List<Map<String, Object>> allLists() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MailingList list : lists.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", list.getId());
            m.put("name", list.getName());
            m.put("description", nz(list.getDescription()));
            m.put("kind", nz(list.getKind()));
            m.put("members", members.countByListId(list.getId()));
            m.put("mailable", members.countMailable(list.getId()));
            m.put("createdAt", list.getCreatedAt() == null ? "" : list.getCreatedAt().format(STAMP));
            m.put("createdBy", nz(list.getCreatedBy()));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/lists")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> createList(@RequestParam String name,
                                        @RequestParam(required = false) String description,
                                        @RequestParam(defaultValue = "IMPORT") String kind) {
        String clean = name.trim();
        if (clean.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        if (lists.existsByName(clean))
            return ResponseEntity.badRequest().body(Map.of("error", "A list called \"" + clean + "\" already exists."));

        MailingList list = lists.save(new MailingList(clean, description, kind, AuditService.currentActor()));
        audit.record("LIST_CREATED", clean, null);
        return ResponseEntity.ok(Map.of("id", list.getId(), "message", "List created."));
    }

    @PostMapping("/lists/delete")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> deleteList(@RequestParam Long id) {
        MailingList list = lists.findById(id).orElse(null);
        if (list == null) return ResponseEntity.badRequest().body(Map.of("error", "No such list."));
        members.deleteByListId(id);
        lists.deleteById(id);
        audit.record("LIST_DELETED", list.getName(), "Subscribers themselves were kept.");
        return ResponseEntity.ok(Map.of("message", "List deleted. The people on it were kept."));
    }

    @PostMapping("/lists/import")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_WRITE')")
    public ResponseEntity<?> importCsv(@RequestParam("file") MultipartFile file,
                                       @RequestParam Long listId,
                                       @RequestParam(required = false) String source) {
        MailingList list = lists.findById(listId).orElse(null);
        if (list == null) return ResponseEntity.badRequest().body(Map.of("error", "No such list."));
        try {
            var result = subscriberService.importCsv(file, listId,
                    source == null || source.isBlank() ? list.getName() : source);
            audit.record("CSV_IMPORTED", list.getName(),
                    result.created() + " created, " + result.addedToList() + " added to list");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("created", result.created());
            body.put("updated", result.updated());
            body.put("addedToList", result.addedToList());
            body.put("alreadyOnList", result.alreadyOnList());
            body.put("suppressed", result.suppressed());
            body.put("invalid", result.invalid());
            body.put("duplicateInFile", result.duplicateInFile());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ---------------- subscribers ----------------

    @GetMapping("/subscribers")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_READ')")
    public Map<String, Object> searchSubscribers(@RequestParam(defaultValue = "") String q,
                                                 @RequestParam(defaultValue = "") String status,
                                                 @RequestParam(required = false) Long listId,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        Page<Subscriber> found = subscribers.search(q, status, listId,
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "id")));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Subscriber s : found.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("email", s.getEmail());
            m.put("name", s.getDisplayName());
            m.put("company", nz(s.getCompany()));
            m.put("phone", nz(s.getPhone()));
            m.put("source", nz(s.getSource()));
            m.put("status", s.getStatus());
            m.put("sent", s.getTotalSent());
            m.put("opened", s.getTotalOpened());
            m.put("clicked", s.getTotalClicked());
            m.put("lastEngagedAt", s.getLastEngagedAt() == null ? "" : s.getLastEngagedAt().format(STAMP));
            m.put("createdAt", s.getCreatedAt() == null ? "" : s.getCreatedAt().format(STAMP));
            rows.add(m);
        }
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }

    @PostMapping("/subscribers")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_WRITE')")
    public ResponseEntity<?> addSubscriber(@RequestParam String email,
                                           @RequestParam(required = false) String firstName,
                                           @RequestParam(required = false) String lastName,
                                           @RequestParam(required = false) Long listId) {
        if (!com.jarurat.mailer.services.SesSender.EMAIL_OK.matcher(email.trim().toLowerCase()).matches())
            return ResponseEntity.badRequest().body(Map.of("error", "That is not a valid email address."));

        Subscriber s = subscriberService.upsert(email, firstName, lastName, "manual");
        boolean added = listId != null && subscriberService.addToList(listId, s.getId());
        audit.record("SUBSCRIBER_ADDED", s.getEmail(), listId == null ? null : "added to list " + listId);
        return ResponseEntity.ok(Map.of("id", s.getId(),
                "message", added ? "Added and put on the list." : "Subscriber saved."));
    }

    @PostMapping("/subscribers/delete")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_WRITE')")
    public ResponseEntity<?> deleteSubscriber(@RequestParam Long id) {
        Subscriber s = subscribers.findById(id).orElse(null);
        if (s == null) return ResponseEntity.badRequest().body(Map.of("error", "No such subscriber."));
        subscriberService.delete(id);
        audit.record("SUBSCRIBER_DELETED", s.getEmail(), null);
        return ResponseEntity.ok(Map.of("message", s.getEmail() + " deleted."));
    }

    @PostMapping("/subscribers/add-to-list")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> addToList(@RequestParam Long subscriberId, @RequestParam Long listId) {
        boolean added = subscriberService.addToList(listId, subscriberId);
        return ResponseEntity.ok(Map.of("message", added ? "Added to the list." : "Already on that list."));
    }

    @PostMapping("/subscribers/remove-from-list")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> removeFromList(@RequestParam Long subscriberId, @RequestParam Long listId) {
        subscriberService.removeFromList(listId, subscriberId);
        return ResponseEntity.ok(Map.of("message", "Removed from the list."));
    }

    @GetMapping("/subscribers/export")
    @PreAuthorize("hasAuthority('SUBSCRIBERS_READ')")
    public void export(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(required = false) Long listId,
                       HttpServletResponse response) throws Exception {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"subscribers.csv\"");

        PrintWriter writer = response.getWriter();
        writer.println("Email,First Name,Last Name,Company,Phone,Status,Source,Sent,Opened,Clicked");

        int page = 0;
        while (true) {
            Page<Subscriber> chunk = subscribers.search(q, status, listId, PageRequest.of(page, 500));
            if (chunk.isEmpty()) break;
            for (Subscriber s : chunk) {
                writer.println(String.join(",",
                        csv(s.getEmail()), csv(s.getFirstName()), csv(s.getLastName()),
                        csv(s.getCompany()), csv(s.getPhone()), csv(s.getStatus()), csv(s.getSource()),
                        String.valueOf(s.getTotalSent()), String.valueOf(s.getTotalOpened()),
                        String.valueOf(s.getTotalClicked())));
            }
            if (!chunk.hasNext()) break;
            page++;
        }
    }

    // ---------------- suppression ----------------

    @GetMapping("/suppressions")
    @PreAuthorize("hasAuthority('SUPPRESSION_READ')")
    public Map<String, Object> suppressions(@RequestParam(defaultValue = "") String q,
                                            @RequestParam(defaultValue = "") String reason,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        Page<GlobalSuppression> found = suppressionRepo.search(q, reason,
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (GlobalSuppression s : found.getContent()) {
            rows.add(Map.of("email", s.getEmail(), "reason", nz(s.getReason()),
                    "at", s.getTimestamp() == null ? "" : s.getTimestamp().format(STAMP)));
        }
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }

    @PostMapping("/suppressions/add")
    @PreAuthorize("hasAuthority('SUPPRESSION_WRITE')")
    public ResponseEntity<?> addSuppression(@RequestParam String email) {
        suppression.suppress(email, "MANUAL");
        audit.record("SUPPRESSED", email.trim().toLowerCase(), "manual");
        return ResponseEntity.ok(Map.of("message", email.trim() + " will never receive campaigns again."));
    }

    @PostMapping("/suppressions/remove")
    @PreAuthorize("hasAuthority('SUPPRESSION_WRITE')")
    public ResponseEntity<?> removeSuppression(@RequestParam String email) {
        suppression.unsuppress(email);
        audit.record("UNSUPPRESSED", email.trim().toLowerCase(), null);
        return ResponseEntity.ok(Map.of("message", email.trim() + " removed from the suppression list."));
    }

    /** Quotes a cell and defuses the leading characters Excel treats as a formula. */
    public static String csv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
