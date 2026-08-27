package com.jarurat.mailer.security;

/**
 * Fine grained rights. Endpoints check these rather than role names, so adding a
 * role later never means hunting through every controller.
 */
public enum Permission {
    SUBSCRIBERS_READ,
    SUBSCRIBERS_WRITE,
    LISTS_READ,
    LISTS_WRITE,
    CAMPAIGNS_READ,
    CAMPAIGNS_WRITE,
    CAMPAIGNS_SEND,
    TEMPLATES_READ,
    TEMPLATES_WRITE,
    TRANSACTIONAL_READ,
    TRANSACTIONAL_SEND,
    SUPPRESSION_READ,
    SUPPRESSION_WRITE,
    TEAM_READ,
    TEAM_WRITE,
    APIKEYS_MANAGE,
    AUDIT_READ,
    SETTINGS_WRITE,
    ANALYTICS_READ,
    MESSAGELOG_READ,
    VERIFICATION_RUN,
    MAIL_READ,
    MAIL_SEND,

    // Creates and deletes real mailboxes on the mail server itself, which is a
    // different blast radius from anything else here: a wrong move stops mail for
    // a live address. Deliberately not granted to MARKETER or HR.
    MAILBOX_MANAGE
}
