package com.jarurat.mailer.mail;

import java.time.Instant;
import java.util.List;

/**
 * What a message list row needs and nothing more. Deliberately has no body: the
 * list view fetches hundreds of these and pulling bodies would move megabytes
 * per page for text nobody has asked to read yet.
 */
public record MessageSummary(String id, String threadId, String subject,
                             List<MailAddress> from, List<MailAddress> to, List<MailAddress> cc,
                             String preview, Instant receivedAt, long size,
                             boolean seen, boolean flagged, boolean answered, boolean draft,
                             boolean hasAttachment, List<String> folderIds) {

    /** The one address a list row shows. Sent and Drafts want the recipient, everything else the sender. */
    public MailAddress counterparty(boolean outgoing) {
        List<MailAddress> side = outgoing ? to : from;
        if (side != null && !side.isEmpty()) return side.get(0);
        return new MailAddress(null, "");
    }
}
