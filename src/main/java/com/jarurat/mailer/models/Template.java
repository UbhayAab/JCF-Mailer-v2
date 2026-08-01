package com.jarurat.mailer.models;

import jakarta.persistence.*;

@Entity
public class Template {
    @Id
    private Long id; // We will just use ID 1 for the active template
    
    private String subject;
    
    @Column(columnDefinition = "TEXT") // TEXT allows for massive HTML strings
    private String htmlBody;

    public Template() {}

    public Template(Long id, String subject, String htmlBody) {
        this.id = id;
        this.subject = subject;
        this.htmlBody = htmlBody;
    }

    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public String getHtmlBody() { return htmlBody; }

    public void setSubject(String subject) { this.subject = subject; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
}