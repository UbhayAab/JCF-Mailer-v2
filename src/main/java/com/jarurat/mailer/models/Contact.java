package com.jarurat.mailer.models;

import jakarta.persistence.*;

@Entity
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String email;
    private String name;
    private String status; // e.g., "CLEAN", "SUPPRESSED"

    // Empty constructor required by Spring
    public Contact() {}

    public Contact(String email, String name, String status) {
        this.email = email;
        this.name = name;
        this.status = status;
    }

    // Getters
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getStatus() { return status; }

    // Setters
    public void setStatus(String status) { this.status = status; }
}