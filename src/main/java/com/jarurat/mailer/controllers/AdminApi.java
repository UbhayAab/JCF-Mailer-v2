package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.ApiKey;
import com.jarurat.mailer.models.AuditLog;
import com.jarurat.mailer.models.User;
import com.jarurat.mailer.repositories.ApiKeyRepository;
import com.jarurat.mailer.repositories.AuditLogRepository;
import com.jarurat.mailer.repositories.UserRepository;
import com.jarurat.mailer.security.ApiKeyHasher;
import com.jarurat.mailer.security.Role;
import com.jarurat.mailer.services.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

import static com.jarurat.mailer.controllers.OverviewApi.STAMP;
import static com.jarurat.mailer.controllers.OverviewApi.nz;

@RestController
@RequestMapping("/api/admin")
public class AdminApi {

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final AuditLogRepository auditLogs;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public AdminApi(UserRepository users, ApiKeyRepository apiKeys, AuditLogRepository auditLogs,
                    PasswordEncoder passwordEncoder, AuditService audit) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.auditLogs = auditLogs;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    // ---------------- roles ----------------

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('TEAM_READ')")
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Role role : Role.values()) {
            out.add(Map.of(
                    "name", role.name(),
                    "label", role.getLabel(),
                    "description", role.getDescription(),
                    "permissions", role.getPermissions().stream().map(Enum::name).sorted().toList()));
        }
        return out;
    }

    // ---------------- team ----------------

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('TEAM_READ')")
    public List<Map<String, Object>> team() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.findAllByOrderByCreatedAtAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("email", u.getEmail());
            m.put("fullName", u.getFullName());
            m.put("role", u.getRole().name());
            m.put("roleLabel", u.getRole().getLabel());
            m.put("active", u.isActive());
            m.put("locked", u.isLocked());
            m.put("lastLoginAt", u.getLastLoginAt() == null ? "never" : u.getLastLoginAt().format(STAMP));
            m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().format(STAMP));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/users/invite")
    @PreAuthorize("hasAuthority('TEAM_WRITE')")
    public ResponseEntity<?> invite(@RequestParam String email,
                                    @RequestParam(required = false) String fullName,
                                    @RequestParam String role,
                                    @RequestParam String password) {
        String clean = email.trim().toLowerCase();
        if (users.existsByEmail(clean))
            return ResponseEntity.badRequest().body(Map.of("error", "That address already has an account."));
        if (password.length() < 10)
            return ResponseEntity.badRequest().body(Map.of("error", "Use at least 10 characters."));

        Role parsed;
        try { parsed = Role.parse(role); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role."));
        }
        if (parsed == Role.OWNER)
            return ResponseEntity.badRequest().body(Map.of("error",
                    "There can only be one owner. Create an Admin instead."));

        User user = new User(clean, passwordEncoder.encode(password), fullName, parsed,
                AuditService.currentActor());
        user.setMustChangePassword(true);
        users.save(user);

        audit.record("USER_INVITED", clean, "role " + parsed.name());
        return ResponseEntity.ok(Map.of("message", clean + " can now sign in as " + parsed.getLabel() + "."));
    }

    @PostMapping("/users/role")
    @PreAuthorize("hasAuthority('TEAM_WRITE')")
    public ResponseEntity<?> changeRole(@RequestParam Long id, @RequestParam String role) {
        User user = users.findById(id).orElse(null);
        if (user == null) return ResponseEntity.badRequest().body(Map.of("error", "No such user."));
        if (user.getRole() == Role.OWNER)
            return ResponseEntity.badRequest().body(Map.of("error", "The owner's role cannot be changed."));

        Role parsed;
        try { parsed = Role.parse(role); }
        catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role."));
        }
        if (parsed == Role.OWNER)
            return ResponseEntity.badRequest().body(Map.of("error", "Ownership cannot be granted this way."));

        user.setRole(parsed);
        users.save(user);
        audit.record("USER_ROLE_CHANGED", user.getEmail(), "now " + parsed.name());
        return ResponseEntity.ok(Map.of("message", user.getEmail() + " is now " + parsed.getLabel() + "."));
    }

    @PostMapping("/users/active")
    @PreAuthorize("hasAuthority('TEAM_WRITE')")
    public ResponseEntity<?> setActive(@RequestParam Long id, @RequestParam boolean active) {
        User user = users.findById(id).orElse(null);
        if (user == null) return ResponseEntity.badRequest().body(Map.of("error", "No such user."));
        if (user.getRole() == Role.OWNER)
            return ResponseEntity.badRequest().body(Map.of("error", "The owner cannot be deactivated."));

        user.setActive(active);
        if (active) { user.setLockedUntil(null); user.setFailedLoginAttempts(0); }
        users.save(user);
        audit.record(active ? "USER_ACTIVATED" : "USER_DEACTIVATED", user.getEmail(), null);
        return ResponseEntity.ok(Map.of("message",
                user.getEmail() + (active ? " reactivated." : " can no longer sign in.")));
    }

    @PostMapping("/users/password")
    @PreAuthorize("hasAuthority('TEAM_WRITE')")
    public ResponseEntity<?> resetPassword(@RequestParam Long id, @RequestParam String password) {
        User user = users.findById(id).orElse(null);
        if (user == null) return ResponseEntity.badRequest().body(Map.of("error", "No such user."));
        if (password.length() < 10)
            return ResponseEntity.badRequest().body(Map.of("error", "Use at least 10 characters."));

        user.setPasswordHash(passwordEncoder.encode(password));
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        users.save(user);
        audit.record("USER_PASSWORD_RESET", user.getEmail(), null);
        return ResponseEntity.ok(Map.of("message", "Password updated for " + user.getEmail() + "."));
    }

    // ---------------- API keys ----------------

    @GetMapping("/api-keys")
    @PreAuthorize("hasAuthority('APIKEYS_MANAGE')")
    public List<Map<String, Object>> keys() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey k : apiKeys.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("prefix", k.getPrefix() + "...");
            m.put("createdAt", k.getCreatedAt() == null ? "" : k.getCreatedAt().format(STAMP));
            m.put("createdBy", nz(k.getCreatedBy()));
            m.put("lastUsedAt", k.getLastUsedAt() == null ? "never" : k.getLastUsedAt().format(STAMP));
            m.put("useCount", k.getUseCount());
            m.put("revoked", k.isRevoked());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/api-keys")
    @PreAuthorize("hasAuthority('APIKEYS_MANAGE')")
    public ResponseEntity<?> createKey(@RequestParam String name) {
        if (name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Give the key a name."));

        String secret = ApiKeyHasher.generate();
        apiKeys.save(new ApiKey(name.trim(), ApiKeyHasher.sha256(secret),
                ApiKeyHasher.prefixOf(secret), AuditService.currentActor()));
        audit.record("APIKEY_CREATED", name.trim(), null);

        // Shown exactly once. Only the hash is retained.
        return ResponseEntity.ok(Map.of("key", secret,
                "message", "Copy this now. It cannot be shown again."));
    }

    @PostMapping("/api-keys/revoke")
    @PreAuthorize("hasAuthority('APIKEYS_MANAGE')")
    public ResponseEntity<?> revokeKey(@RequestParam Long id) {
        ApiKey key = apiKeys.findById(id).orElse(null);
        if (key == null) return ResponseEntity.badRequest().body(Map.of("error", "No such key."));
        key.setRevokedAt(LocalDateTime.now());
        apiKeys.save(key);
        audit.record("APIKEY_REVOKED", key.getName(), null);
        return ResponseEntity.ok(Map.of("message", "Key revoked. Calls using it now fail."));
    }

    // ---------------- audit ----------------

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public Map<String, Object> auditTrail(@RequestParam(defaultValue = "") String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        Page<AuditLog> found = auditLogs.search(q,
                PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size))));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuditLog a : found.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("actor", nz(a.getActor()));
            m.put("action", nz(a.getAction()));
            m.put("target", nz(a.getTarget()));
            m.put("detail", nz(a.getDetail()));
            m.put("ip", nz(a.getIp()));
            m.put("at", a.getTimestamp() == null ? "" : a.getTimestamp().format(STAMP));
            rows.add(m);
        }
        return Map.of("rows", rows, "page", found.getNumber(),
                "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements());
    }
}
