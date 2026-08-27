package com.jarurat.mailer.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One live answer per address, keyed by the address itself the same way
 * GlobalSuppression is. Two things fall out of that choice: re-verifying simply
 * overwrites, and a list that shares addresses with another list is only ever
 * checked once.
 *
 * checkedAt is what makes results expire. An address that resolved a year ago
 * proves nothing today, so the read path always carries a freshness cutoff
 * rather than trusting whatever is in the row.
 */
@Entity
@Table(name = "verification_result", indexes = {
        @Index(name = "idx_verification_verdict", columnList = "verdict"),
        @Index(name = "idx_verification_checked", columnList = "checkedAt")
})
public class VerificationResult {

    @Id
    private String email; // Email is the primary key

    @Column(nullable = false)
    private String verdict; // DELIVERABLE | RISKY | UNDELIVERABLE

    @Column(length = 200)
    private String reason;

    private Boolean syntaxOk;
    private Boolean domainOk;
    private Boolean mxOk;
    private Boolean mailboxOk;

    private boolean disposable;
    private boolean roleAccount;
    private boolean catchAll;

    @Column(length = 255)
    private String mxHost;

    private String checkedBy;

    private LocalDateTime checkedAt = LocalDateTime.now();

    public VerificationResult() {}

    public VerificationResult(EmailCheck check, String checkedBy) {
        this.email = check.email();
        this.checkedBy = checkedBy;
        apply(check);
    }

    /** Re-verification overwrites in place, so the row count tracks the audience. */
    public void apply(EmailCheck check) {
        this.verdict = check.verdict().name();
        this.reason = truncate(check.reason(), 200);
        this.syntaxOk = check.syntaxOk();
        this.domainOk = check.domainOk();
        this.mxOk = check.mxOk();
        this.mailboxOk = check.mailboxOk();
        this.disposable = check.disposable();
        this.roleAccount = check.roleAccount();
        this.catchAll = check.catchAll();
        this.mxHost = truncate(check.mxHost(), 255);
        this.checkedAt = LocalDateTime.now();
    }

    public EmailCheck toCheck() {
        return new EmailCheck(email, Verdict.parse(verdict), reason,
                syntaxOk, domainOk, mxOk, mailboxOk,
                disposable, roleAccount, catchAll, false, mxHost);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public String getEmail() { return email; }
    public String getVerdict() { return verdict; }
    public String getReason() { return reason; }
    public Boolean getSyntaxOk() { return syntaxOk; }
    public Boolean getDomainOk() { return domainOk; }
    public Boolean getMxOk() { return mxOk; }
    public Boolean getMailboxOk() { return mailboxOk; }
    public boolean isDisposable() { return disposable; }
    public boolean isRoleAccount() { return roleAccount; }
    public boolean isCatchAll() { return catchAll; }
    public String getMxHost() { return mxHost; }
    public String getCheckedBy() { return checkedBy; }
    public LocalDateTime getCheckedAt() { return checkedAt; }

    public void setCheckedBy(String checkedBy) { this.checkedBy = checkedBy; }
}
