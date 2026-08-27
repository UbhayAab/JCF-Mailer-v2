package com.jarurat.mailer.mail;

import java.util.Optional;
import java.util.Set;

/**
 * Where the webmail gets the secret it presents to Stalwart on the user's behalf.
 *
 * This is an interface because the answer we ship today is the wrong one and we
 * know it. Right now the app authenticates to the mail server with the user's own
 * mailbox password over HTTP Basic, which means that password has to be captured
 * at console login and held for the length of the session. Two things are wrong
 * with that: the app can read a mailbox password it has no business knowing, and
 * revoking mail access means changing the password rather than dropping a token.
 *
 * The correct long-term answer is OAUTHBEARER. Stalwart accepts OAuth bearer
 * tokens, so Campaign Studio should become the OIDC provider, mint a short lived,
 * mail-scoped access token for the signed-in user at login, and hand that over
 * here instead. Then no mailbox password is ever stored anywhere in this process,
 * a heap dump is worth nothing, and revoking a session is a server-side act.
 * Making that switch touches exactly two things: the implementation behind this
 * interface, and the one line in JmapClient that builds the Authorization header.
 * Nothing else in the package knows what the secret is. That is the whole point
 * of the indirection, and why callers see "secret" rather than "password".
 */
public interface MailCredentialStore {

    /** Empty when this user has not unlocked their mailbox in this session yet. */
    Optional<String> secretFor(String user);

    void remember(String user, String secret);

    void forget(String user);

    boolean knows(String user);

    /**
     * Which mailboxes this process can currently act for. On the interface rather
     * than only the implementation because MailService.listFoldersForAll needs it
     * to build the mailboxes overview, and a caller holding the interface must not
     * have to downcast to ask.
     */
    Set<String> knownUsers();
}
