package com.jarurat.mailer.verification;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One address, fully judged. The three Boolean columns are deliberately boxed:
 * null means "never got that far", which the console renders as a dash rather
 * than as a failure. Conflating "not checked" with "failed" is how a
 * verification tool starts throwing away good addresses.
 *
 * @param transientFailure true when the answer is an artefact of our own
 *                         infrastructure (DNS timeout, socket error) rather than
 *                         anything about the address. Those are never persisted,
 *                         so the next run retries instead of inheriting a lie.
 */
public record EmailCheck(String email,
                         Verdict verdict,
                         String reason,
                         Boolean syntaxOk,
                         Boolean domainOk,
                         Boolean mxOk,
                         Boolean mailboxOk,
                         boolean disposable,
                         boolean roleAccount,
                         boolean catchAll,
                         boolean transientFailure,
                         String mxHost) {

    /** The exact row shape the Verify list table consumes. */
    public Map<String, Object> toRow() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", email);
        m.put("verdict", verdict.name());
        m.put("code", verdict.getCode());
        m.put("label", verdict.getLabel());
        m.put("reason", reason);
        m.put("syntax", mark(syntaxOk));
        m.put("mx", mark(mxOk));
        m.put("mailbox", mark(mailboxOk));
        m.put("disposable", disposable);
        m.put("roleAccount", roleAccount);
        m.put("catchAll", catchAll);
        m.put("mxHost", mxHost == null ? "" : mxHost);
        return m;
    }

    /** "ok" | "no" | "" so the table can render the same tick, cross and dash. */
    static String mark(Boolean value) {
        if (value == null) return "";
        return value ? "ok" : "no";
    }
}
