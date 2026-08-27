package com.jarurat.mailer.analytics;

/**
 * A single "opens" number has been meaningless since Apple shipped Mail Privacy
 * Protection in 2021 and started pre-fetching every tracking pixel whether or not
 * the recipient ever looked at the message. Splitting the hit into buckets is the
 * only way an open rate stays comparable year to year.
 *
 * The bucket is stored on every row rather than applied at read time, so a later,
 * better classifier can rewrite history from the raw signals instead of throwing
 * the old numbers away.
 */
public enum OpenClassification {

    HUMAN("Reliable", "A person almost certainly rendered the message"),
    APPLE_MPP("Apple MPP", "Apple Mail Privacy Protection pre-fetched the pixel"),
    PROXY("Proxy prefetch", "A mailbox provider cached the image before the recipient saw it"),
    BOT("Bot", "A scanner, crawler or script fetched the pixel");

    private final String label;
    private final String description;

    OpenClassification(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }

    /** The only bucket allowed to move an open rate or a click rate. */
    public boolean countsAsEngagement() { return this == HUMAN; }

    public static OpenClassification parse(String value) {
        if (value == null) return BOT;
        for (OpenClassification c : values()) {
            if (c.name().equalsIgnoreCase(value.trim())) return c;
        }
        return BOT;
    }
}
