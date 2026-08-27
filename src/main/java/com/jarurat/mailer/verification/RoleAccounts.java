package com.jarurat.mailer.verification;

import java.util.Set;

/**
 * Shared department inboxes. They are usually real and usually deliver, which is
 * why they are RISKY rather than UNDELIVERABLE, but they are read by whoever is
 * on duty, forwarded to a ticket queue, or nobody, so they drag engagement rates
 * down and they are the addresses most likely to be sitting on a spam trap list.
 */
public final class RoleAccounts {

    private static final Set<String> EXACT = Set.of(
            "abuse", "admin", "administrator", "all", "billing", "board", "careers",
            "ceo", "compliance", "contact", "contactus", "customercare", "customerservice",
            "dev", "devnull", "director", "donations", "enquiries", "enquiry", "everyone",
            "feedback", "finance", "ftp", "grievance", "help", "helpdesk", "hostmaster",
            "hr", "info", "information", "inquiries", "inquiry", "it", "jobs", "legal",
            "mail", "mailer-daemon", "marketing", "media", "newsletter", "noc", "office",
            "operations", "orders", "partner", "partners", "partnership", "partnerships",
            "payments", "postmaster", "press", "privacy", "purchase", "purchasing",
            "recruitment", "register", "root", "sales", "security", "service", "services",
            "spam", "subscribe", "support", "sysadmin", "team", "tech", "unsubscribe",
            "usenet", "uucp", "webmaster", "welcome", "www"
    );

    /** Everything that starts one of these is a send-only address by construction. */
    private static final String[] PREFIXES = {
            "no-reply", "noreply", "no_reply", "donotreply", "do-not-reply", "do_not_reply",
            "mailer-daemon", "postmaster", "abuse", "bounce"
    };

    private RoleAccounts() {}

    public static boolean isRole(String localPart) {
        if (localPart == null || localPart.isBlank()) return false;
        String clean = localPart.trim().toLowerCase();

        // Gmail style tags are noise for this decision: info+events@ is still info@
        int plus = clean.indexOf('+');
        if (plus > 0) clean = clean.substring(0, plus);

        if (EXACT.contains(clean)) return true;
        for (String prefix : PREFIXES) {
            if (clean.startsWith(prefix)) return true;
        }
        return false;
    }
}
