package com.jarurat.mailer.mail;

/**
 * One part of a message that is not body text. The bytes are not carried here:
 * fetch them with JmapClient.download(user, blobId, name, type) when the user
 * actually asks for them, so opening a message never drags megabytes into heap.
 */
public record Attachment(String partId, String blobId, String name, String type,
                         long size, String disposition, String cid) {

    /**
     * Inline parts are images the HTML body references with a cid: URL. They are
     * attachments as far as JMAP is concerned but must not be listed as files.
     */
    public boolean isInline() {
        return (cid != null && !cid.isBlank()) || "inline".equalsIgnoreCase(disposition);
    }

    /**
     * Never null, and never anything the sender chose verbatim.
     *
     * This lands in a Content-Disposition header, so the raw MIME filename is
     * attacker controlled input on a response header: a quote or a CR lets the
     * sender close the parameter or split the header, and path separators let
     * them suggest a filename that escapes the download folder. Only a plain
     * basename survives.
     */
    public String safeName() {
        if (name == null || name.isBlank()) return "attachment";

        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);

        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length() && out.length() < 120; i++) {
            char c = base.charAt(i);
            // Letters, digits and a short punctuation set. Everything else, including
            // quotes, semicolons, control characters and CR/LF, is replaced.
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' ' ? c : '_');
        }

        String cleaned = out.toString().trim();
        // A name of only dots would still address a directory.
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "attachment" : cleaned;
    }
}
