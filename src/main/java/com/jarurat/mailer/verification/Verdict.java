package com.jarurat.mailer.verification;

import java.util.Arrays;

/**
 * Three buckets, not a 0-100 score. A score invites whoever wires the dispatcher
 * to invent their own threshold at send time; a bucket forces that judgement to
 * be made once, here, where the reasons are known.
 */
public enum Verdict {

    DELIVERABLE("ok", "Deliverable"),
    RISKY("wa", "Risky"),
    UNDELIVERABLE("no", "Undeliverable");

    /** Matches the pill class names the console already styles. */
    private final String code;
    private final String label;

    Verdict(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }

    public static Verdict parse(String value) {
        return Arrays.stream(values())
                .filter(v -> v.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown verdict: " + value));
    }
}
