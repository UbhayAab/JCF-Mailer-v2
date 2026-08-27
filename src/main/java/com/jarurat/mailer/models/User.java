package com.jarurat.mailer.models;

import com.jarurat.mailer.security.Role;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_user") // "user" is reserved in PostgreSQL
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private boolean active = true;

    /** Forces a password change on next login for invited accounts. */
    private boolean mustChangePassword = false;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastLoginAt;
    private String createdBy;

    private int failedLoginAttempts = 0;
    private LocalDateTime lockedUntil;

    public User() {}

    public User(String email, String passwordHash, String fullName, Role role, String createdBy) {
        this.email = email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName == null || fullName.isBlank() ? email : fullName; }
    public Role getRole() { return role; }
    public boolean isActive() { return active; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public String getCreatedBy() { return createdBy; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public void setEmail(String email) { this.email = email.trim().toLowerCase(); }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setRole(Role role) { this.role = role; }
    public void setActive(boolean active) { this.active = active; }
    public void setMustChangePassword(boolean v) { this.mustChangePassword = v; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setFailedLoginAttempts(int n) { this.failedLoginAttempts = n; }
    public void setLockedUntil(LocalDateTime lockedUntil) { this.lockedUntil = lockedUntil; }
}
