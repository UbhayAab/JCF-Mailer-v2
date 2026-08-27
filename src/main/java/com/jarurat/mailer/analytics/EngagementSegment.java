package com.jarurat.mailer.analytics;

/**
 * The named answers to "what actually happened to the people I mailed".
 *
 * The first four are mutually exclusive and between them account for everybody who
 * was delivered to. The rest overlap with those four and with each other, because a
 * person can click a link and then unsubscribe, and a report that pretends otherwise
 * is hiding the most interesting thing in it.
 *
 * The one that matters most is PRIVACY_UNKNOWN. Apple's Mail Privacy Protection
 * fetches the tracking pixel whether or not a human ever looked, so for those people
 * "opened" and "did not open" are both unsupported claims. Folding them into either
 * side would make the other number a lie, so they get their own bucket and the size
 * of the doubt is reported rather than buried.
 */
public enum EngagementSegment {

    CLICKED("Clicked", true,
            "Followed a link. The only unambiguous act of human intent in email."),

    OPENED_NOT_CLICKED("Opened, did nothing", true,
            "A verified human open with no link follow. Usually the largest group."),

    PRIVACY_UNKNOWN("Unknown - privacy protected", true,
            "Only a machine fetched the pixel. Whether a person read it cannot be known, "
            + "and counting them either way would be a claim the data does not support."),

    NOT_OPENED("No open recorded", true,
            "Delivered, and nothing came back at all. Includes anyone reading with images "
            + "off, so treat it as absence of evidence rather than evidence of absence."),

    BOUNCED("Bounced", false,
            "The receiving server rejected it permanently."),

    UNSUBSCRIBED("Unsubscribed", false,
            "Asked to stop hearing from us."),

    COMPLAINED("Marked as spam", false,
            "Reported the message. The most damaging signal a send can produce."),

    FAILED("Failed to send", false,
            "The send itself did not succeed, so there was never a message to open."),

    SKIPPED("Skipped", false,
            "Suppressed between being queued and being sent, so deliberately not mailed.");

    private final String label;
    private final boolean exclusive;
    private final String description;

    EngagementSegment(String label, boolean exclusive, String description) {
        this.label = label;
        this.exclusive = exclusive;
        this.description = description;
    }

    public String getLabel() { return label; }

    /** True for the four that partition everyone delivered to, exactly once each. */
    public boolean isExclusive() { return exclusive; }

    public String getDescription() { return description; }

    /**
     * Whether saving this segment as a mailing list would hand someone an audience
     * they must not mail. A list of bounced addresses is guaranteed to bounce again,
     * and doing that repeatedly is how a sending domain loses its reputation.
     */
    public boolean isUnmailable() {
        return this == BOUNCED || this == COMPLAINED;
    }

    public static EngagementSegment parse(String value) {
        if (value == null) throw new IllegalArgumentException("Say which group you mean.");
        for (EngagementSegment s : values()) if (s.name().equalsIgnoreCase(value.trim())) return s;
        throw new IllegalArgumentException("There is no group called \"" + value + "\".");
    }
}
