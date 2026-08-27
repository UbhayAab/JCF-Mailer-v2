package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Who did what, from where. Required to answer "who sent that blast?". */
@Entity
@Table(name = "audit_log", indexes = @Index(name = "idx_audit_time", columnList = "timestamp"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String actor;
    private String action;
    private String target;

    @Column(length = 1000)
    private String detail;

    private String ip;
    private LocalDateTime timestamp = LocalDateTime.now();

    public AuditLog() {}

    public AuditLog(String actor, String action, String target, String detail, String ip) {
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000);
        this.ip = ip;
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getDetail() { return detail; }
    public String getIp() { return ip; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
