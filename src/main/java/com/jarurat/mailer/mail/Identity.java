package com.jarurat.mailer.mail;

/**
 * A From address the signed-in user is allowed to send as. Stalwart decides this,
 * not us, which is why the compose screen must populate its From dropdown from
 * here instead of from anything in our own user table.
 */
public record Identity(String id, String name, String email,
                       String replyTo, String textSignature, String htmlSignature) {

    public MailAddress asAddress() { return new MailAddress(name, email); }
}
