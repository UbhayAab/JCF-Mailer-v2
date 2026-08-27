package com.jarurat.mailer.mail;

import java.util.List;

/**
 * One page of a folder listing or a search. Stalwart only reports total when the
 * query asks for it, so this record carries it explicitly rather than leaving the
 * UI to guess whether a next page exists.
 */
public record MessagePage(List<MessageSummary> messages, int offset, int limit, int total) {

    public static MessagePage empty(int offset, int limit) {
        return new MessagePage(List.of(), offset, limit, 0);
    }

    public boolean hasMore() { return offset + messages.size() < total; }

    public int nextOffset() { return offset + messages.size(); }
}
