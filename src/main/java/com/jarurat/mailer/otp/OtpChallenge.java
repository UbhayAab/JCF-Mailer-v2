package com.jarurat.mailer.otp;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One issued code. The code itself is never stored.
 *
 * A six digit code has about twenty bits of entropy, so no amount of hashing work
 * would save it from an attacker who steals this table and can compute a million
 * guesses. What protects it is that the hash is keyed with a pepper held outside the
 * database, and bound to this row's own id and address, so a hash lifted from one row
 * cannot even be tested against another.
 *
 * This row is also the rate limiter. Every request writes one, whether or not a
 * message went out, so counting rows over a time window is an exact limit that costs
 * no extra state and survives a restart.
 */
@Entity
@Table(name = "otp_challenge", indexes = {
        @Index(name = "idx_otp_public", columnList = "publicId", unique = true),
        @Index(name = "idx_otp_lookup", columnList = "email,purpose,createdAt"),
        @Index(name = "idx_otp_key", columnList = "apiKeyName,createdAt"),
        @Index(name = "idx_otp_expiry", columnList = "expiresAt")
})
public class OtpChallenge {

    /** Sent to the caller, unguessable, and safe to put in a browser. */
    @Column(nullable = false, unique = true, length = 48)
    private String publicId;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(nullable = false, length = 24)
    private String purpose;

    /** HMAC-SHA256 of publicId, email and the code, keyed with the server pepper. */
    @Column(nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false, length = 12)
    private String codeFormat = "digits6";

    @Column(length = 160)
    private String apiKeyName;

    @Column(nullable = false, length = 100)
    private String templateSlug;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** Set the moment a correct code is accepted, which is what makes it single use. */
    private LocalDateTime consumedAt;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(nullable = false)
    private Integer maxAttempts = 5;

    @Column(nullable = false)
    private Integer sends = 1;

    @Column(nullable = false)
    private LocalDateTime lastSentAt = LocalDateTime.now();

    /**
     * SENT | SUPPRESSED | FAILED | BLOCKED_RATE. Never told to the caller: the whole
     * point of the uniform response is that a request for an unknown address and a
     * request for a real one look identical from outside.
     */
    @Column(nullable = false, length = 16)
    private String sendStatus = "SENT";

    @Column(length = 255)
    private String sesMessageId;

    /** Proof, handed to the calling system, that this address really did verify. */
    @Column(length = 64)
    private String verificationTokenHash;

    private LocalDateTime verificationTokenExpiresAt;
    private LocalDateTime verificationTokenUsedAt;

    @Column(length = 64)
    private String requestIp;

    @Version
    private Long version;

    public OtpChallenge() {}

    public OtpChallenge(String publicId, String email, String purpose, String codeHash,
                        String codeFormat, String templateSlug, String apiKeyName,
                        LocalDateTime expiresAt, String requestIp) {
        this.publicId = publicId;
        this.email = email;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.codeFormat = codeFormat;
        this.templateSlug = templateSlug;
        this.apiKeyName = apiKeyName;
        this.expiresAt = expiresAt;
        this.requestIp = requestIp == null || requestIp.length() <= 64 ? requestIp : requestIp.substring(0, 64);
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getEmail() { return email; }
    public String getPurpose() { return purpose; }
    public String getCodeHash() { return codeHash; }
    public String getCodeFormat() { return codeFormat; }
    public String getApiKeyName() { return apiKeyName; }
    public String getTemplateSlug() { return templateSlug; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public int getAttempts() { return attempts == null ? 0 : attempts; }
    public int getMaxAttempts() { return maxAttempts == null ? 5 : maxAttempts; }
    public int getSends() { return sends == null ? 1 : sends; }
    public LocalDateTime getLastSentAt() { return lastSentAt; }
    public String getSendStatus() { return sendStatus; }
    public String getSesMessageId() { return sesMessageId; }
    public String getVerificationTokenHash() { return verificationTokenHash; }
    public LocalDateTime getVerificationTokenExpiresAt() { return verificationTokenExpiresAt; }
    public LocalDateTime getVerificationTokenUsedAt() { return verificationTokenUsedAt; }
    public String getRequestIp() { return requestIp; }

    public boolean isExpired(LocalDateTime now) { return expiresAt != null && !expiresAt.isAfter(now); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean isOutOfAttempts() { return getAttempts() >= getMaxAttempts(); }

    /** Live means a code could still be accepted against it. */
    public boolean isLive(LocalDateTime now) {
        return !isConsumed() && !isExpired(now) && !isOutOfAttempts();
    }

    /**
     * A resend mints a new code, because only the HMAC was kept and the original is
     * genuinely unrecoverable. The attempt counter resets with it: the attempts made
     * against the previous code are not evidence about this one.
     */
    public void replaceCode(String newHash) {
        this.codeHash = newHash;
        this.attempts = 0;
    }

    public void setConsumedAt(LocalDateTime v) { this.consumedAt = v; }
    public void setAttempts(int v) { this.attempts = v; }
    public void setSends(int v) { this.sends = v; }
    public void setLastSentAt(LocalDateTime v) { this.lastSentAt = v; }
    public void setSendStatus(String v) { this.sendStatus = v; }
    public void setSesMessageId(String v) { this.sesMessageId = v; }
    public void setVerificationTokenHash(String v) { this.verificationTokenHash = v; }
    public void setVerificationTokenExpiresAt(LocalDateTime v) { this.verificationTokenExpiresAt = v; }
    public void setVerificationTokenUsedAt(LocalDateTime v) { this.verificationTokenUsedAt = v; }
}
