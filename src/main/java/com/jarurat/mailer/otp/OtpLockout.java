package com.jarurat.mailer.otp;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * An address that has failed too many codes.
 *
 * Its own table rather than a column on the challenge, because a lockout has to
 * outlive the challenges that caused it. Those are purged after thirty days; a
 * lockout that vanished with them would hand a patient attacker a clean slate.
 */
@Entity
@Table(name = "otp_lockout")
public class OtpLockout {

    @Id
    @Column(length = 254)
    private String email;

    @Column(nullable = false)
    private LocalDateTime lockedUntil;

    @Column(length = 64)
    private String reason;

    @Column(nullable = false)
    private Integer failedCount = 0;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public OtpLockout() {}

    public OtpLockout(String email, LocalDateTime lockedUntil, String reason, int failedCount) {
        this.email = email;
        this.lockedUntil = lockedUntil;
        this.reason = reason;
        this.failedCount = failedCount;
    }

    public String getEmail() { return email; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public String getReason() { return reason; }
    public int getFailedCount() { return failedCount == null ? 0 : failedCount; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void setLockedUntil(LocalDateTime v) { this.lockedUntil = v; }
    public void setReason(String v) { this.reason = v; }
    public void setFailedCount(int v) { this.failedCount = v; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
