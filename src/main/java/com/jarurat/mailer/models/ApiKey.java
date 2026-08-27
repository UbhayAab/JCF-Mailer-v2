package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Machine credential for the transactional API, so the HR system can send
 * interview mail without a human session. The secret itself is never stored,
 * only its SHA-256 hash; the prefix exists so the console can show which key
 * is which.
 */
@Entity
@Table(name = "api_key", indexes = @Index(name = "idx_apikey_hash", columnList = "keyHash"))
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String keyHash;

    /** First 12 characters of the key, safe to display. */
    @Column(nullable = false)
    private String prefix;

    private LocalDateTime createdAt = LocalDateTime.now();
    private String createdBy;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private long useCount = 0;

    public ApiKey() {}

    public ApiKey(String name, String keyHash, String prefix, String createdBy) {
        this.name = name;
        this.keyHash = keyHash;
        this.prefix = prefix;
        this.createdBy = createdBy;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getKeyHash() { return keyHash; }
    public String getPrefix() { return prefix; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public long getUseCount() { return useCount; }
    public boolean isRevoked() { return revokedAt != null; }

    public void setLastUsedAt(LocalDateTime t) { this.lastUsedAt = t; }
    public void setRevokedAt(LocalDateTime t) { this.revokedAt = t; }
    public void setUseCount(long n) { this.useCount = n; }
}
