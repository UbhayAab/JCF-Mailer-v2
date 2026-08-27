package com.jarurat.mailer.verification;

import org.springframework.stereotype.Component;

/**
 * The single address check, cheapest stage first.
 *
 * Order matters for cost, not just for tidiness. Syntax is free and rules out
 * the address outright. The disposable and role lookups are in-memory set hits.
 * DNS is a network round trip but it is cached per domain, so a list of twelve
 * thousand addresses across four hundred domains pays for four hundred lookups.
 * The SMTP conversation is the only genuinely expensive stage and it is off by
 * default, see SmtpProbe for why.
 *
 * Anything that fails for our own reasons rather than the address's reasons is
 * flagged transient and never written to the results table, so a DNS blip does
 * not permanently condemn a good address.
 */
@Component
public class EmailVerifier {

    private final DisposableDomains disposableDomains;
    private final DnsLookup dns;
    private final SmtpProbe smtp;

    public EmailVerifier(DisposableDomains disposableDomains, DnsLookup dns, SmtpProbe smtp) {
        this.disposableDomains = disposableDomains;
        this.dns = dns;
        this.smtp = smtp;
    }

    public EmailCheck check(String rawEmail) {
        String email = normalise(rawEmail);

        // Stage 1: syntax. Nothing else is knowable if this fails.
        if (!EmailSyntax.isValid(email)) {
            return new EmailCheck(email, Verdict.UNDELIVERABLE, "Malformed address",
                    false, null, null, null, false, false, false, false, null);
        }

        String local = EmailSyntax.localPart(email);
        String domain = EmailSyntax.domainPart(email);

        // Stage 2 and 3: free set lookups. Recorded now, judged at the end, so
        // the report still shows the DNS columns for a disposable address.
        boolean disposable = disposableDomains.contains(domain);
        boolean role = RoleAccounts.isRole(local);

        // Stage 4: DNS, cached per domain.
        DnsLookup.DomainInfo info = dns.lookup(domain);

        if (info.status() == DnsLookup.Status.UNKNOWN) {
            return new EmailCheck(email, Verdict.RISKY, "DNS lookup failed, not yet checked",
                    true, null, null, null, disposable, role, false, true, null);
        }
        if (info.status() == DnsLookup.Status.NXDOMAIN) {
            return new EmailCheck(email, Verdict.UNDELIVERABLE, "Domain does not resolve",
                    true, false, null, null, disposable, role, false, false, null);
        }
        if (info.nullMx()) {
            // RFC 7505: the domain has published that it accepts no mail at all.
            return new EmailCheck(email, Verdict.UNDELIVERABLE, "Domain publishes a null MX",
                    true, true, false, null, disposable, role, false, false, null);
        }
        if (!info.hasMx() && !info.hasAddress()) {
            return new EmailCheck(email, Verdict.UNDELIVERABLE, "No MX record",
                    true, true, false, null, disposable, role, false, false, null);
        }

        Boolean mxOk = info.hasMx();
        String mxHost = info.primaryMx();

        if (disposable) {
            return new EmailCheck(email, Verdict.UNDELIVERABLE, "Disposable provider",
                    true, true, mxOk, null, true, role, false, false, mxHost);
        }

        // Stage 5: the SMTP conversation, opt-in.
        Boolean mailboxOk = null;
        boolean catchAll = false;
        if (smtp.isEnabled() && info.hasMx()) {
            SmtpProbe.Outcome outcome = smtp.probe(domain, email, mxHost);
            mailboxOk = outcome.mailboxOk();
            catchAll = outcome.catchAll();

            if (Boolean.FALSE.equals(mailboxOk)) {
                return new EmailCheck(email, Verdict.UNDELIVERABLE, "Mailbox rejected by the receiving server",
                        true, true, mxOk, false, false, role, false, false, mxHost);
            }
        }

        if (catchAll) {
            return new EmailCheck(email, Verdict.RISKY, "Catch-all domain, cannot confirm",
                    true, true, mxOk, null, false, role, true, false, mxHost);
        }
        if (!info.hasMx()) {
            // Legal under RFC 5321 but it means the domain never configured mail
            // on purpose, and those A records are usually a parked web host.
            return new EmailCheck(email, Verdict.RISKY, "No MX record, delivery would fall back to the domain A record",
                    true, true, false, mailboxOk, false, role, false, false, null);
        }
        if (role) {
            return new EmailCheck(email, Verdict.RISKY, "Role account - low engagement",
                    true, true, mxOk, mailboxOk, false, true, false, false, mxHost);
        }
        if (Boolean.TRUE.equals(mailboxOk)) {
            return new EmailCheck(email, Verdict.DELIVERABLE, "Valid mailbox",
                    true, true, mxOk, true, false, false, false, false, mxHost);
        }

        // The honest wording for the default path. Without an SMTP conversation
        // we know the domain accepts mail, not that this mailbox exists, and
        // saying "Valid mailbox" here would be a claim we never tested.
        return new EmailCheck(email, Verdict.DELIVERABLE, "Domain accepts mail, syntax and MX both check out",
                true, true, mxOk, null, false, false, false, false, mxHost);
    }

    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }
}
