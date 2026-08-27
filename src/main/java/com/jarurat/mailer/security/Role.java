package com.jarurat.mailer.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static com.jarurat.mailer.security.Permission.*;

/**
 * HR deliberately cannot see the marketing subscriber base, and marketing
 * deliberately cannot fire transactional mail at candidates. Separating them is
 * the whole point of having roles.
 */
public enum Role {

    OWNER("Owner", "Full control, including team and ownership transfer",
            EnumSet.allOf(Permission.class)),

    ADMIN("Admin", "Everything except transferring ownership",
            EnumSet.of(SUBSCRIBERS_READ, SUBSCRIBERS_WRITE, LISTS_READ, LISTS_WRITE,
                    CAMPAIGNS_READ, CAMPAIGNS_WRITE, CAMPAIGNS_SEND,
                    TEMPLATES_READ, TEMPLATES_WRITE,
                    TRANSACTIONAL_READ, TRANSACTIONAL_SEND,
                    SUPPRESSION_READ, SUPPRESSION_WRITE,
                    TEAM_READ, TEAM_WRITE, APIKEYS_MANAGE, AUDIT_READ, SETTINGS_WRITE,
                    ANALYTICS_READ, MESSAGELOG_READ, VERIFICATION_RUN, MAIL_READ, MAIL_SEND,
                    MAILBOX_MANAGE)),

    MARKETER("Marketer", "Builds and sends campaigns to subscriber lists",
            EnumSet.of(SUBSCRIBERS_READ, SUBSCRIBERS_WRITE, LISTS_READ, LISTS_WRITE,
                    CAMPAIGNS_READ, CAMPAIGNS_WRITE, CAMPAIGNS_SEND,
                    TEMPLATES_READ, TEMPLATES_WRITE,
                    SUPPRESSION_READ, SUPPRESSION_WRITE,
                    ANALYTICS_READ, MESSAGELOG_READ, VERIFICATION_RUN, MAIL_READ, MAIL_SEND)),

    // Mail yes, transactional yes, and nothing that reaches the subscriber base.
    // MESSAGELOG_READ, ANALYTICS_READ and VERIFICATION_RUN are all withheld because
    // each one lists campaign recipient addresses, which is that base by another route.
    HR("HR", "Sends transactional candidate mail, no access to the marketing base",
            EnumSet.of(TEMPLATES_READ, TEMPLATES_WRITE,
                    TRANSACTIONAL_READ, TRANSACTIONAL_SEND,
                    MAIL_READ, MAIL_SEND)),

    // Reporting only. VERIFICATION_RUN is left out because it is a verb: it opens
    // SMTP probes from our sending IP, which is a reputation action, not a read.
    VIEWER("Viewer", "Read only across reporting",
            EnumSet.of(SUBSCRIBERS_READ, LISTS_READ, CAMPAIGNS_READ,
                    TEMPLATES_READ, TRANSACTIONAL_READ, SUPPRESSION_READ,
                    ANALYTICS_READ, MESSAGELOG_READ));

    private final String label;
    private final String description;
    private final Set<Permission> permissions;

    Role(String label, String description, Set<Permission> permissions) {
        this.label = label;
        this.description = description;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public Set<Permission> getPermissions() { return permissions; }

    public boolean can(Permission permission) { return permissions.contains(permission); }

    public static Role parse(String value) {
        return Arrays.stream(values())
                .filter(r -> r.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown role: " + value));
    }
}
