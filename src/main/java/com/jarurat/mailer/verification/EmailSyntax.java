package com.jarurat.mailer.verification;

/**
 * RFC 5321/5322 shaped, not the usual one-line regex. The regex we already use
 * for CSV import (SesSender.EMAIL_OK) is a cheap "does it look like an address"
 * filter and happily accepts things no MTA will route, for example a 400
 * character local part or a label ending in a hyphen. Verification is the one
 * place where being strict is the whole product, so this walks the string.
 *
 * Deliberately ASCII only. SMTPUTF8 addresses are legal but SES will not accept
 * them from us, so treating them as valid here would produce a "Deliverable"
 * verdict for something we can never deliver.
 */
public final class EmailSyntax {

    private static final String ATEXT =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+-/=?^_`{|}~";

    private EmailSyntax() {}

    public static boolean isValid(String email) {
        if (email == null) return false;
        // RFC 5321 caps the whole forward-path at 254 octets
        if (email.isEmpty() || email.length() > 254) return false;

        int at = splitPoint(email);
        if (at <= 0 || at == email.length() - 1) return false;

        return localOk(email.substring(0, at)) && domainOk(email.substring(at + 1));
    }

    public static String localPart(String email) {
        int at = splitPoint(email);
        return at <= 0 ? "" : email.substring(0, at);
    }

    public static String domainPart(String email) {
        int at = splitPoint(email);
        return at < 0 || at == email.length() - 1 ? "" : email.substring(at + 1);
    }

    /** Last '@' wins, because a quoted local part is allowed to contain one. */
    private static int splitPoint(String email) {
        return email == null ? -1 : email.lastIndexOf('@');
    }

    private static boolean localOk(String local) {
        if (local.isEmpty() || local.length() > 64) return false;

        if (local.charAt(0) == '"' && local.charAt(local.length() - 1) == '"') return quotedOk(local);

        if (local.charAt(0) == '.' || local.charAt(local.length() - 1) == '.') return false;
        if (local.contains("..")) return false;

        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c == '.') continue;
            if (ATEXT.indexOf(c) < 0) return false;
        }
        return true;
    }

    private static boolean quotedOk(String local) {
        if (local.length() < 3) return false; // "" is legal but addresses nothing

        boolean escaped = false;
        for (int i = 1; i < local.length() - 1; i++) {
            char c = local.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') return false;
            if (c < 32 || c > 126) return false;
        }
        return !escaped;
    }

    private static boolean domainOk(String domain) {
        if (domain.isEmpty() || domain.length() > 253) return false;
        if (domain.charAt(0) == '.' || domain.charAt(domain.length() - 1) == '.') return false;
        if (domain.contains("..")) return false;

        String[] labels = domain.split("\\.", -1);
        // A bare hostname cannot receive internet mail, and an address literal
        // like [10.0.0.1] has no MX to look up, so both are rejected here.
        if (labels.length < 2) return false;

        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) return false;
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') return false;
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-';
                if (!ok) return false;
            }
        }

        String tld = labels[labels.length - 1];
        if (tld.length() < 2) return false;

        // Punycode TLDs such as xn--p1ai contain digits, so only an entirely
        // numeric last label is rejected. That one means someone typed an IP.
        boolean allDigits = true;
        for (int i = 0; i < tld.length(); i++) {
            if (!Character.isDigit(tld.charAt(i))) { allDigits = false; break; }
        }
        return !allDigits;
    }
}
