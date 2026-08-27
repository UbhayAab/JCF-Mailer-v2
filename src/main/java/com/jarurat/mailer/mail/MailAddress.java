package com.jarurat.mailer.mail;

/** One RFC 5322 address. JMAP always hands these over as {name, email} pairs. */
public record MailAddress(String name, String email) {

    public static MailAddress of(String email) { return new MailAddress(null, email); }

    /** Falls back to the address so a reading pane never renders a blank sender. */
    public String display() {
        return name == null || name.isBlank() ? email : name;
    }

    /** Header form, e.g. {@code "Priyanka" <priyanka@jarurat.care>}. */
    public String formatted() {
        if (name == null || name.isBlank()) return email;
        return "\"" + name.replace("\"", "") + "\" <" + email + ">";
    }
}
