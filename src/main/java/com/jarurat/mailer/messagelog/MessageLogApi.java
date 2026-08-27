package com.jarurat.mailer.messagelog;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "did that email actually go out" screen.
 *
 * Read only by design. Nothing here can resend, delete or edit a row, because
 * the value of a delivery log is that it cannot be tidied up after the fact.
 */
@RestController
@RequestMapping("/api/messagelog")
public class MessageLogApi {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss");

    private final MessageLogService messageLog;

    public MessageLogApi(MessageLogService messageLog) {
        this.messageLog = messageLog;
    }

    /**
     * q searches recipient, sender, subject and both message ids at once, which
     * is what someone pasting an unknown identifier into the box actually wants.
     * The named filters are there for when they know which field they mean.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('MESSAGELOG_READ')")
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(name = "to", defaultValue = "") String toAddr,
                                      @RequestParam(name = "from", defaultValue = "") String fromAddr,
                                      @RequestParam(defaultValue = "") String subject,
                                      @RequestParam(defaultValue = "") String messageId,
                                      @RequestParam(defaultValue = "") String direction,
                                      @RequestParam(defaultValue = "") String outcome,
                                      @RequestParam(required = false) Long campaignId,
                                      @RequestParam(defaultValue = "") String since,
                                      @RequestParam(defaultValue = "") String until,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {

        MessageLogService.Filter filter = new MessageLogService.Filter(
                q, toAddr, fromAddr, subject, messageId, direction, outcome, campaignId,
                parseStart(since), parseEnd(until));

        Page<MessageLogEntry> found = messageLog.search(filter, page, size);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MessageLogEntry entry : found.getContent()) rows.add(row(entry));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("page", found.getNumber());
        out.put("totalPages", found.getTotalPages());
        out.put("totalElements", found.getTotalElements());
        out.put("outcomes", messageLog.outcomes());
        out.put("retentionDays", messageLog.getRetentionDays());
        return out;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('MESSAGELOG_READ')")
    public Map<String, Object> summary(@RequestParam(defaultValue = "") String since,
                                       @RequestParam(defaultValue = "") String until) {
        return messageLog.summary(parseStart(since), parseEnd(until));
    }

    @GetMapping("/{id:[0-9]+}")
    @PreAuthorize("hasAuthority('MESSAGELOG_READ')")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        MessageLogEntry entry = messageLog.find(id);
        if (entry == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No message with that id."));
        }

        Map<String, Object> m = row(entry);
        // The list view truncates for the table; the detail view is the whole
        // answer, so it repeats the raw fields in full.
        m.put("serverResponse", nz(entry.getServerResponse()));
        m.put("reportingMta", nz(entry.getReportingMta()));
        m.put("outcomeAt", entry.getOutcomeAt() == null ? "" : entry.getOutcomeAt().format(STAMP));
        m.put("timestampIso", entry.getTimestamp() == null ? "" : entry.getTimestamp().toString());
        return ResponseEntity.ok(m);
    }

    private Map<String, Object> row(MessageLogEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.getId());
        m.put("at", entry.getTimestamp() == null ? "" : entry.getTimestamp().format(STAMP));
        m.put("direction", nz(entry.getDirection()));
        m.put("from", nz(entry.getFromEmail()));
        m.put("to", nz(entry.getToEmail()));
        m.put("subject", nz(entry.getSubject()));
        m.put("outcome", nz(entry.getOutcome()));
        m.put("serverResponse", clip(entry.getServerResponse(), 160));
        m.put("messageId", nz(entry.getMessageId()));
        m.put("sesMessageId", nz(entry.getSesMessageId()));
        m.put("campaignId", entry.getCampaignId());
        m.put("latencyMs", entry.getLatencyMs());
        m.put("actor", nz(entry.getActor()));
        return m;
    }

    /** Accepts "2026-08-15" or "2026-08-15T09:30". A bare date means the start of that day. */
    private static LocalDateTime parseStart(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            return value.length() == 10 ? LocalDate.parse(value).atStartOfDay()
                                        : LocalDateTime.parse(value);
        } catch (Exception ignored) {
            // A malformed date should widen the search, not 500 the page.
            return null;
        }
    }

    /** A bare date as the upper bound has to mean the end of that day, not midnight. */
    private static LocalDateTime parseEnd(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        try {
            return value.length() == 10 ? LocalDate.parse(value).atTime(23, 59, 59)
                                        : LocalDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
