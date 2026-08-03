package com.jarurat.mailer.models;
import jakarta.persistence.*;

@Entity
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String campaignName; // Isolates contact to a specific campaign
    private String email;
    private String name;
    private String status; // CLEAN or SENT
    private String clickedUrl; // Tracks what they clicked!
    private String unsubscribeToken = java.util.UUID.randomUUID().toString();

    public Contact() {}
    public Contact(String campaignName, String email, String name, String status) {
        this.campaignName = campaignName; this.email = email; this.name = name; this.status = status;
    }

    public Long getId() { return id; }
    public String getCampaignName() { return campaignName; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getUnsubscribeToken() { return unsubscribeToken; }
    public String getClickedUrl() { return clickedUrl; }

    public void setStatus(String status) { this.status = status; }
    public void setClickedUrl(String clickedUrl) { this.clickedUrl = clickedUrl; }
}