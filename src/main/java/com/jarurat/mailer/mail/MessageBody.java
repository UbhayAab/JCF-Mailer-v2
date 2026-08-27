package com.jarurat.mailer.mail;

import java.time.Instant;
import java.util.List;

/**
 * A single message opened for reading. Both html and text may be present, either
 * may be null, and callers must not assume html exists just because the message
 * came from a mail client: Stalwart returns the same plain part under both
 * textBody and htmlBody when a message has no HTML alternative, and this record
 * has already resolved that (html is null unless a real text/html part existed).
 */
public record MessageBody(String id, String threadId, String subject,
                          List<MailAddress> from, List<MailAddress> to,
                          List<MailAddress> cc, List<MailAddress> replyTo,
                          Instant sentAt, Instant receivedAt,
                          String html, String text,
                          List<Attachment> attachments, List<String> folderIds,
                          boolean seen, boolean flagged, boolean draft,
                          String messageId, List<String> inReplyTo, List<String> references) {

    public boolean hasHtml() { return html != null && !html.isBlank(); }

    /** Files worth listing under the message, i.e. everything except inline cid: images. */
    public List<Attachment> files() {
        return attachments == null ? List.of() : attachments.stream().filter(a -> !a.isInline()).toList();
    }
}
