package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailCredentialStore;
import com.jarurat.mailer.mail.MailException;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Decides which mailbox a webmail request is allowed to act on.
 *
 * This exists because of a gap between two identity systems that nothing else in
 * the app bridges. Campaign Studio authenticates a console user against app_user;
 * Stalwart authenticates a mailbox against its own store, and JmapClient needs
 * that mailbox secret on every call. Nothing in the login flow captures it, so
 * without this class every /api/mail call fails with MailException.AUTH and the
 * screen is dead on arrival. The mailbox is therefore opened explicitly, once per
 * browser session, and the address is then pinned in the HTTP session.
 *
 * Pinning it server side is the security point. Every data endpoint asks this
 * class which mailbox it is serving and no endpoint accepts a mailbox parameter,
 * so a signed-in user cannot read another mailbox by editing a request: they would
 * have to go through open() and produce that mailbox's password.
 *
 * Holding a mailbox password in heap at all is the temporary arrangement described
 * on MailCredentialStore, and switching to OAUTHBEARER retires open() entirely.
 */
@Component
public class MailboxAccess {

    /** Named for the session attribute it owns. Read nowhere else. */
    static final String SESSION_KEY = "jarurat.mail.mailbox";

    private final MailCredentialStore credentials;
    private final JmapClient client;
    private final String domain;

    public MailboxAccess(MailCredentialStore credentials,
                         JmapClient client,
                         @Value("${jarurat.mail.domain:jarurat.care}") String domain) {
        this.credentials = credentials;
        this.client = client;
        this.domain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The open mailbox for this browser session, or null when none is open.
     *
     * Only an explicit session pin counts. There used to be a fallback here that
     * granted access whenever the console login name happened to match a mailbox
     * the credential store already knew, on the theory that the two identity
     * systems would converge. That was an authentication bypass, not a
     * convenience: the store is process wide, so once any user opened hr@ every
     * console account signed in as hr@ inherited that mailbox without ever
     * producing its password. Whoever opens a mailbox proves the password, and
     * nobody rides along on a name collision.
     */
    public String current(Authentication auth, HttpSession session) {
        Object pinned = session == null ? null : session.getAttribute(SESSION_KEY);
        if (pinned instanceof String mailbox && credentials.knows(mailbox)) {
            return mailbox.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    public String require(Authentication auth, HttpSession session) {
        String mailbox = current(auth, session);
        if (mailbox == null) {
            throw new MailboxLockedException("Open your mailbox to read mail on this device.");
        }
        return mailbox;
    }

    /**
     * Verifies the secret against Stalwart before trusting it, and returns the
     * address that was opened.
     *
     * The check runs through JmapClient.probe, which builds its Authorization
     * header from the secret passed in rather than from the credential store. The
     * store is process wide and these are shared mailboxes, so writing an
     * unverified secret into it first, as this method used to, meant a wrong
     * password authenticated every other request for the length of one round trip
     * and evicted a working credential on the way out. Nothing is written now
     * until Stalwart has accepted it.
     *
     * The cached session is still dropped afterwards: JmapClient caches one
     * session document per user for the life of the process, and a credential
     * change has to invalidate it.
     */
    public String open(HttpSession session, String mailbox, String secret) {
        String address = normalise(mailbox);
        if (secret == null || secret.isEmpty()) {
            throw new MailException(MailException.Kind.AUTH, "Enter the mailbox password.");
        }
        if (!client.probe(address, secret)) {
            throw new MailException(MailException.Kind.AUTH, "That mailbox password was not accepted.");
        }

        credentials.remember(address, secret);
        client.forgetSession(address);
        session.setAttribute(SESSION_KEY, address);
        return address;
    }

    /**
     * Drops the credential, not just this session's pointer to it.
     *
     * That is deliberate even though these are shared mailboxes and it can log a
     * colleague's tab out of support@ as well. Sign out has to mean the password is
     * gone from this process; anything less makes the button a lie, and the cost of
     * being wrong the other way is one re-entry.
     */
    public void close(Authentication auth, HttpSession session) {
        String mailbox = current(auth, session);
        if (session != null) session.removeAttribute(SESSION_KEY);
        if (mailbox != null) {
            credentials.forget(mailbox);
            client.forgetSession(mailbox);
        }
    }

    /**
     * Only real addresses, and only on our own domain unless the domain lock is
     * cleared. Stalwart holds no accounts anywhere else, so an unrestricted address
     * here would just be a way for a signed-in user to fill this process's credential
     * map with junk keys.
     */
    private String normalise(String mailbox) {
        String address = mailbox == null ? "" : mailbox.trim().toLowerCase(Locale.ROOT);
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1
                || address.indexOf(' ') >= 0) {
            throw new MailException(MailException.Kind.PROTOCOL, "That is not a mailbox address.");
        }
        if (!domain.isEmpty() && !address.endsWith("@" + domain)) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "Only " + domain + " mailboxes can be opened here.");
        }
        return address;
    }

    /** No mailbox is open, so the screen should ask for one rather than show an error. */
    public static class MailboxLockedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public MailboxLockedException(String message) {
            super(message);
        }
    }
}
