package com.jarurat.mailer.journey;

/**
 * The predefined condition catalogue. Deliberately a fixed list rather than a query
 * builder: every entry maps to one predicate over data the platform already collects,
 * and every entry has a defined answer for the awkward case where the signal has not
 * arrived yet.
 *
 * Each condition also names the sheet a matching person lands in, which is how the
 * flowchart and the conditional sheets stay the same thing rather than two views that
 * drift apart.
 */
public enum ConditionType {

    OPENED("Opened", JourneyBucket.OPENED_NOT_CLICKED,
            "A verified human open. Apple's pre-fetch and scanner traffic do not count."),

    CLICKED("Clicked any link", JourneyBucket.CLICKED,
            "Followed any tracked link in the measured message."),

    CLICKED_SPECIFIC("Clicked a specific link", JourneyBucket.CLICKED,
            "Followed one named link. Use it to separate a registration click from a footer click."),

    OPENED_NOT_CLICKED("Opened but did not click", JourneyBucket.OPENED_NOT_CLICKED,
            "Read it and did nothing. The largest and most useful branch in most journeys."),

    NOT_OPENED("Did not open", JourneyBucket.NOT_OPENED,
            "Delivered, and no verified open. Includes people reading with images off, "
            + "so treat it as no signal rather than as proof of indifference."),

    NOT_DELIVERED("Was not delivered", JourneyBucket.NOT_DELIVERED,
            "The send failed, was skipped, or bounced before delivery."),

    BOUNCED("Bounced", JourneyBucket.BOUNCED,
            "The receiving server rejected it permanently."),

    UNSUBSCRIBED("Unsubscribed", JourneyBucket.UNSUBSCRIBED,
            "Asked to stop hearing from us."),

    COMPLAINED("Marked as spam", JourneyBucket.COMPLAINED,
            "Reported the message. Terminal, and worth an alert."),

    PRIVACY_UNKNOWN("Privacy protected", JourneyBucket.PRIVACY_UNKNOWN,
            "Only a machine fetched the pixel, so neither opened nor not-opened is honest."),

    /**
     * Every CONDITION node carries one of these as its last branch. Without it a
     * person who matches nothing has nowhere to go, and "stuck with no outgoing edge"
     * should be impossible by construction rather than a state to debug.
     */
    ELSE("Everyone else", JourneyBucket.NONE,
            "The catch-all. Every condition node must have one.");

    private final String label;
    private final JourneyBucket bucket;
    private final String description;

    ConditionType(String label, JourneyBucket bucket, String description) {
        this.label = label;
        this.bucket = bucket;
        this.description = description;
    }

    public String getLabel() { return label; }

    /** The sheet a person matching this condition is placed in. */
    public JourneyBucket getBucket() { return bucket; }

    public String getDescription() { return description; }

    /** CLICKED_SPECIFIC needs a URL; nothing else takes an argument. */
    public boolean needsArgument() { return this == CLICKED_SPECIFIC; }

    public static ConditionType parse(String value) {
        if (value == null) return ELSE;
        for (ConditionType c : values()) if (c.name().equalsIgnoreCase(value.trim())) return c;
        return ELSE;
    }
}
