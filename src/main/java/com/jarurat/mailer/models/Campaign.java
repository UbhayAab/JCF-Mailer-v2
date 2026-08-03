package com.jarurat.mailer.models;
import jakarta.persistence.*;

@Entity
public class Campaign {
    @Id
    private String name; // e.g., "EarlyAccess_Aug"
    private String subject;
    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    public Campaign() {}
    public Campaign(String name, String subject, String htmlBody) { this.name = name; this.subject = subject; this.htmlBody = htmlBody; }

    public String getName() { return name; }
    public String getSubject() { return subject; }
    public String getHtmlBody() { return htmlBody; }
    public void setSubject(String subject) { this.subject = subject; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
}