package com.jarurat.mailer.directory;

import com.jarurat.mailer.directory.StalwartAdminService.MailboxSummary;
import com.jarurat.mailer.directory.StalwartAdminService.StalwartAdminException;
import com.jarurat.mailer.services.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON behind the mailbox administration screen.
 *
 * Every method needs MAILBOX_MANAGE, which no role holds by default. This is the
 * one surface in the console that can hand somebody a working mailbox on the
 * organisation's own domain, so it is gated separately from TEAM_WRITE and from
 * the mail permissions: being able to read support@ is not the same thing as
 * being able to create it.
 *
 * Nothing that goes out of here carries a password, an access token or the
 * refresh token. Passwords arrive in a request body, go straight into the service
 * and are never echoed, never audited and never put in an error.
 */
@RestController
@RequestMapping("/api/admin/mailboxes")
public class StalwartAdminApi {

    private static final long GIB = 1024L * 1024 * 1024;

    /** Matches the quota Stalwart's own accounts were created with. */
    private static final double DEFAULT_QUOTA_GB = 2;

    /**
     * The instance's whole disk is smaller than this, so anything above it is a
     * typo (a quota in megabytes, most likely) rather than an intention.
     */
    private static final double MAX_QUOTA_GB = 512;

    private final StalwartAdminService directory;
    private final AuditService audit;

    public StalwartAdminApi(StalwartAdminService directory, AuditService audit) {
        this.directory = directory;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ read

    /**
     * Never fails when the server has no admin credential. The screen wants to say
     * "not configured" in a sentence, and a stack trace behind a spinner would send
     * whoever is looking at it to the mail server logs for nothing.
     */
    @GetMapping("")
    @PreAuthorize("hasAuthority('MAILBOX_MANAGE')")
    public Map<String, Object> list() {
        boolean configured = directory.configured();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", configured);
        out.put("domain", directory.domain());
        out.put("accounts", configured ? rows(directory.list()) : List.of());
        return out;
    }

    // ------------------------------------------------------------------ write

    @PostMapping("")
    @PreAuthorize("hasAuthority('MAILBOX_MANAGE')")
    public Map<String, Object> create(@RequestBody CreateRequest body) {
        String id = directory.create(body.localPart(), body.description(),
                body.password(), quotaBytes(body.quotaGb()));
        String address = body.localPart().trim() + "@" + directory.domain();

        // The password is not an argument to anything that persists. Who created
        // which mailbox is worth keeping; what they set on it is not.
        audit.record("MAILBOX_CREATED", address, "quota " + quotaGb(body.quotaGb()) + " GB");

        return Map.of("id", id, "emailAddress", address);
    }

    /**
     * Its own endpoint rather than a field on a general update, and that is not
     * tidiness. Stalwart rejects a bad patch as a whole, so a password bundled with
     * anything else can silently fail to change while the call still looks like it
     * worked. See StalwartAdminService.setPassword.
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('MAILBOX_MANAGE')")
    public ResponseEntity<Void> setPassword(@PathVariable String id, @RequestBody PasswordRequest body) {
        directory.setPassword(id, body.password());
        audit.record("MAILBOX_PASSWORD_RESET", id, "set from the mailbox admin screen");
        return ResponseEntity.noContent().build();
    }

    /** The list sent is the list kept: aliases missing from the body are removed. */
    @PutMapping("/{id}/aliases")
    @PreAuthorize("hasAuthority('MAILBOX_MANAGE')")
    public ResponseEntity<Void> setAliases(@PathVariable String id, @RequestBody AliasRequest body) {
        List<String> aliases = body.aliases() == null ? List.of() : body.aliases();
        directory.setAliases(id, aliases);
        audit.record("MAILBOX_ALIASES_SET", id,
                aliases.isEmpty() ? "cleared" : String.join(", ", aliases));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MAILBOX_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        directory.delete(id);
        audit.record("MAILBOX_DELETED", id, "removed from the mailbox admin screen");
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ bodies

    /** quotaGb is boxed so that "not sent" and "sent as zero" stay tellable apart. */
    public record CreateRequest(String localPart, String description, String password, Double quotaGb) { }

    public record PasswordRequest(String password) { }

    public record AliasRequest(List<String> aliases) { }

    // ------------------------------------------------------------------ plumbing

    private static double quotaGb(Double requested) {
        if (requested == null || requested <= 0) return DEFAULT_QUOTA_GB;
        return Math.min(MAX_QUOTA_GB, requested);
    }

    private static long quotaBytes(Double requested) {
        return Math.round(quotaGb(requested) * GIB);
    }

    private static List<Map<String, Object>> rows(List<MailboxSummary> accounts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MailboxSummary a : accounts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("localPart", a.localPart());
            m.put("emailAddress", a.emailAddress());
            m.put("description", a.description());
            m.put("aliases", a.aliases());
            m.put("quotaBytes", a.quotaBytes());
            m.put("usedBytes", a.usedBytes());
            m.put("createdAt", a.createdAt());
            // "User", "Group", "List", "Domain". The screen needs it: x:Account/get
            // returns all of them and only a User has a password worth setting.
            m.put("role", a.role());
            rows.add(m);
        }
        return rows;
    }

    // ------------------------------------------------------------------ failures

    /**
     * The service already decided which status its message deserves, so this handler
     * only carries it across. 400 for anything an operator can retype, 502 when
     * Stalwart itself failed, 503 when the box has no admin credential at all.
     */
    @ExceptionHandler(StalwartAdminException.class)
    public ResponseEntity<Map<String, String>> onRefused(StalwartAdminException e) {
        String message = e.getMessage() == null ? "That mailbox change was refused." : e.getMessage();
        return ResponseEntity.status(e.status()).body(Map.of("error", message));
    }

    /** A malformed or missing JSON body, answered in the same shape as everything else here. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> onBadBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Send a JSON body for this request."));
    }

    /**
     * Last resort, so an unexpected fault is a sentence on screen rather than a
     * stack trace naming internal hosts. The message is deliberately the exception
     * class and nothing else: an arbitrary getMessage() from this package could be
     * carrying a URL, a header or worse.
     *
     * The rethrow is not optional. @PreAuthorize denials arrive here as an
     * AccessDeniedException, which is a RuntimeException, so a handler this broad
     * would quietly turn every 403 into a 502. Letting it past returns it to the
     * security filter chain, which is the only thing entitled to answer an
     * authorisation failure.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> onFailure(RuntimeException e) {
        if (e instanceof AccessDeniedException) throw e;
        return ResponseEntity.status(502).body(Map.of("error",
                "The mailbox admin screen hit an unexpected fault: " + e.getClass().getSimpleName()));
    }
}
