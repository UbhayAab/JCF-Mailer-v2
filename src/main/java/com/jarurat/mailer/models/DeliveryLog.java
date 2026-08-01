package com.jarurat.mailer.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class DeliveryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String recipientEmail;
    private String eventType; 
    private LocalDateTime timestamp;

    public DeliveryLog() {}

    public DeliveryLog(String recipientEmail, String eventType, LocalDateTime timestamp) {
        this.recipientEmail = recipientEmail;
        this.eventType = eventType;
        this.timestamp = timestamp;
    }

    // NEW: Getters required for Thymeleaf UI
    public Long getId() { return id; }
    public String getRecipientEmail() { return recipientEmail; }
    public String getEventType() { return eventType; }
    public LocalDateTime getTimestamp() { return timestamp; }
}