package com.jarurat.mailer.mail;

/**
 * A JMAP Mailbox. The role ("inbox", "sent", "drafts", "trash", "junk") is the
 * only stable handle: the id is opaque and is allocated per account, so nothing
 * outside a single user's session may ever be keyed off it.
 */
public record MailFolder(String id, String name, String role, String parentId,
                         int sortOrder, int totalEmails, int unreadEmails) {

    public boolean isRole(String wanted) {
        return role != null && role.equalsIgnoreCase(wanted);
    }

    /** True for folders the user created, which unlike the system five can be renamed or removed. */
    public boolean isUserCreated() { return role == null || role.isBlank(); }
}
