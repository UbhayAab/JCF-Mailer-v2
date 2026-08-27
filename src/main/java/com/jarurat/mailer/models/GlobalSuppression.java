package com.jarurat.mailer.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class GlobalSuppression {
    @Id
    private String email; // Email is the primary key

    private String reason; // UNSUBSCRIBED | BOUNCE | COMPLAINT | MANUAL

    private LocalDateTime timestamp = LocalDateTime.now();

    public GlobalSuppression() {}

    public GlobalSuppression(String email, String reason) {
        this.email = email;
        this.reason = reason;
    }

    public String getEmail() { return email; }
    public String getReason() { return reason; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
