package com.jarurat.mailer.journey;

/**
 * The conditional sheets. One sheet per outcome, per journey, and a person sits in
 * exactly one of them at any moment.
 *
 * The rank is what makes that true. A person who opens a message and then clicks a
 * link satisfies two conditions at once, and a person who opens and then unsubscribes
 * satisfies two that point in opposite directions. Rather than let arrival order
 * decide, every bucket carries a rank and a person only ever moves up it. So the
 * ladder, not the clock, settles "opened AND clicked" as CLICKED and settles
 * "opened AND unsubscribed" as UNSUBSCRIBED.
 *
 * Two separate ideas about leaving, which are easy to confuse:
 *
 *   mustStop - the journey has no choice. An unsubscribe, a hard bounce or a spam
 *              complaint takes the person out immediately whatever the flowchart
 *              says, because continuing would be a legal or deliverability problem.
 *
 *   goal     - the person did the thing the journey wanted. Whether that ends their
 *              treatment is the marketer's call, expressed by pointing the CLICKED
 *              branch at an exit. The editor wires that by default, so the common
 *              case matches the intent, but a journey that keeps talking to people
 *              who clicked is a legitimate design and the engine does not forbid it.
 *
 * The sheets in between - opened but no click, did not open, privacy protected - are
 * working sheets. People sit in them and keep being treated, which is what makes the
 * "did not open, so nudge them again" loop possible at all.
 */
public enum JourneyBucket {

    /** Nothing observed yet. Not a sheet anyone is placed in deliberately. */
    NONE(0, false, false, "No signal yet", "The message went out and nothing has come back."),

    /** SES accepted nothing, or the send failed outright. */
    NOT_DELIVERED(10, false, false, "Not delivered",
            "The send failed or was skipped, so there was never a message to open."),

    NOT_OPENED(20, false, false, "Did not open",
            "Delivered, and no verified human open. Includes people who read with images off."),

    /**
     * Machine-only opens. Apple Mail Privacy Protection pre-fetches the pixel whether
     * or not a person ever looked, so this is genuinely unknowable rather than a
     * softer version of "opened". It sits above NOT_OPENED because it is more
     * evidence, and below OPENED_NOT_CLICKED because it is not evidence of a human.
     */
    PRIVACY_UNKNOWN(30, false, false, "Privacy protected",
            "Only a machine fetched the pixel. Whether a person read it cannot be known."),

    OPENED_NOT_CLICKED(40, false, false, "Opened, no click",
            "A verified human open with no link follow."),

    CLICKED(50, false, true, "Clicked",
            "Followed a link. The only unambiguous act of human intent in email."),

    REPLIED(60, false, true, "Replied",
            "Wrote back. On a small professional list this outranks every counter."),

    BOUNCED(70, true, false, "Bounced",
            "The address is dead. Nothing further is sent to it."),

    UNSUBSCRIBED(80, true, false, "Unsubscribed",
            "Asked to stop. This outranks every engagement signal."),

    COMPLAINED(90, true, false, "Complained",
            "Marked it as spam. The most serious signal there is.");

    private final int rank;
    private final boolean mustStop;
    private final boolean goal;
    private final String label;
    private final String description;

    JourneyBucket(int rank, boolean mustStop, boolean goal, String label, String description) {
        this.rank = rank;
        this.mustStop = mustStop;
        this.goal = goal;
        this.label = label;
        this.description = description;
    }

    public int getRank() { return rank; }

    /** Takes the person out of the flow immediately, whatever the flowchart says. */
    public boolean mustStop() { return mustStop; }

    /** The journey got what it wanted. Whether that ends treatment is the flowchart's call. */
    public boolean isGoal() { return goal; }

    /** True for any sheet a person can be filed under and never treated again. */
    public boolean isTerminal() { return mustStop; }

    public String getLabel() { return label; }
    public String getDescription() { return description; }

    /**
     * The promotion rule. A person only ever moves up the ladder, so the order two
     * signals happen to arrive in can never change where they end up.
     */
    public JourneyBucket promote(JourneyBucket candidate) {
        if (candidate == null) return this;
        return candidate.rank > this.rank ? candidate : this;
    }

    public static JourneyBucket parse(String value) {
        if (value == null) return NONE;
        for (JourneyBucket b : values()) if (b.name().equalsIgnoreCase(value.trim())) return b;
        return NONE;
    }
}
