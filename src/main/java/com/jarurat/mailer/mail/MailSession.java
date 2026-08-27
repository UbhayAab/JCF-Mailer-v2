package com.jarurat.mailer.mail;

import java.net.URI;

/**
 * The parts of the JMAP session document we actually use.
 *
 * accountId is opaque and different for every user (measured on this server:
 * priyanka=g, partnership=h, support=i, hr=e), so it can only ever be read from
 * the session document and never derived from the address or hardcoded.
 *
 * The URLs here have already been re-based onto our own host. Stalwart advertises
 * them as absolute URLs on mail.jarurat.care, and that name does not resolve on
 * the box the app runs on, so using them verbatim would fail DNS.
 */
public record MailSession(String username, String accountId, URI apiUri,
                          String downloadTemplate, String uploadTemplate, String state) {
}
