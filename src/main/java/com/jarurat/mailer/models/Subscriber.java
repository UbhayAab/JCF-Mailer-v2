package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per human, globally. The previous model stored a separate contact row
 * per campaign, so the same person appeared many times and an unsubscribe in one
 * campaign left the duplicates mailable.
 */
@Entity
@Table(name = "subscriber", indexes = {
        @Index(name = "idx_sub_email", columnList = "email", unique = true),
        @Index(name = "idx_sub_status", columnList = "status")
})
public class Subscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    private String firstName;
    private String lastName;
    private String phone;
    private String company;

    /** Where the address came from: an event, a scrape, a newsletter form. */
    private String source;

    /** SUBSCRIBED | UNSUBSCRIBED | BOUNCED | COMPLAINED */
    @Column(nullable = false)
    private String status = "SUBSCRIBED";

    @Column(nullable = false, unique = true)
    private String unsubscribeToken = UUID.randomUUID().toString();

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Aggregate engagement, kept denormalised so list views stay fast. */
    private Integer totalSent = 0;
    private Integer totalOpened = 0;
    private Integer totalClicked = 0;
    private LocalDateTime lastEngagedAt;

    public Subscriber() {}

    public Subscriber(String email, String firstName, String lastName, String source) {
        this.email = email.trim().toLowerCase();
        this.firstName = firstName;
        this.lastName = lastName;
        this.source = source;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getPhone() { return phone; }
    public String getCompany() { return company; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getUnsubscribeToken() { return unsubscribeToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getTotalSent() { return totalSent == null ? 0 : totalSent; }
    public int getTotalOpened() { return totalOpened == null ? 0 : totalOpened; }
    public int getTotalClicked() { return totalClicked == null ? 0 : totalClicked; }
    public LocalDateTime getLastEngagedAt() { return lastEngagedAt; }

    public String getDisplayName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? email : joined;
    }

    public boolean isMailable() { return "SUBSCRIBED".equals(status); }

    public void setEmail(String email) { this.email = email.trim().toLowerCase(); }
    public void setFirstName(String v) { this.firstName = v; }
    public void setLastName(String v) { this.lastName = v; }
    public void setPhone(String v) { this.phone = v; }
    public void setCompany(String v) { this.company = v; }
    public void setSource(String v) { this.source = v; }
    public void setStatus(String v) { this.status = v; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    public void setTotalSent(int v) { this.totalSent = v; }
    public void setTotalOpened(int v) { this.totalOpened = v; }
    public void setTotalClicked(int v) { this.totalClicked = v; }
    public void setLastEngagedAt(LocalDateTime v) { this.lastEngagedAt = v; }
}
