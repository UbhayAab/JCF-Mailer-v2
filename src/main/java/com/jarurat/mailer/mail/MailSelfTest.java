package com.jarurat.mailer.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Proves this package can actually talk to Stalwart, end to end, without a
 * browser and without a main method.
 *
 * It is inert unless jarurat.mail.selftest names a mailbox, so shipping it costs
 * a startup no-op. Run it on the box with:
 *   java -jar mailer.jar --jarurat.mail.selftest=priyanka@jarurat.care \
 *                        --jarurat.mail.selftest-secret=...
 *
 * The secret argument exists only for this test: in the real app the credential
 * arrives from the login flow through MailCredentialStore, and passing a password
 * on a command line is exactly the sort of thing that stops being necessary once
 * MailCredentialStore hands out OAuth tokens instead.
 */
@Component
public class MailSelfTest implements ApplicationRunner {

    /** One line of the report. ok=false means the step failed, not that mail is broken. */
    public record Step(String name, boolean ok, String detail) {
        @Override
        public String toString() { return (ok ? "  ok   " : "  FAIL ") + name + " - " + detail; }
    }

    private final JmapClient client;
    private final MailService mail;
    private final MailCredentialStore credentials;
    private final String user;
    private final String secret;

    public MailSelfTest(JmapClient client, MailService mail, MailCredentialStore credentials,
                        @Value("${jarurat.mail.selftest:}") String user,
                        @Value("${jarurat.mail.selftest-secret:}") String secret) {
        this.client = client;
        this.mail = mail;
        this.credentials = credentials;
        this.user = user;
        this.secret = secret;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (user == null || user.isBlank()) return;
        if (secret != null && !secret.isBlank()) credentials.remember(user, secret);

        System.out.println("Mail self-test against " + client.sessionUri() + " as " + user);
        for (Step step : check(user)) System.out.println(step);
    }

    /**
     * Read-only apart from the last two steps, which flip $flagged on and straight
     * back off again. Nothing here creates, moves or deletes a message: a self-test
     * that can lose someone's mail is worse than no self-test.
     */
    public List<Step> check(String user) {
        List<Step> steps = new ArrayList<>();

        MailSession session;
        try {
            session = client.session(user);
            steps.add(new Step("session", true, "accountId=" + session.accountId()
                    + " api=" + session.apiUri()));
        } catch (RuntimeException e) {
            steps.add(new Step("session", false, describe(e)));
            return steps; // nothing else can work without an account id
        }

        List<MailFolder> folders = List.of();
        try {
            folders = mail.listFolders(user);
            StringBuilder sb = new StringBuilder();
            for (MailFolder f : folders) {
                sb.append(sb.isEmpty() ? "" : ", ")
                  .append(f.name()).append('/').append(f.role())
                  .append('(').append(f.unreadEmails()).append('/').append(f.totalEmails()).append(')');
            }
            steps.add(new Step("listFolders", !folders.isEmpty(), sb.toString()));
        } catch (RuntimeException e) {
            steps.add(new Step("listFolders", false, describe(e)));
        }

        MailFolder inbox = null;
        for (MailFolder f : folders) {
            if (f.isRole("inbox")) inbox = f;
        }

        MessagePage page = MessagePage.empty(0, 5);
        if (inbox != null) {
            try {
                page = mail.listMessages(user, inbox.id(), 0, 5);
                steps.add(new Step("listMessages", true,
                        page.messages().size() + " of " + page.total() + " in Inbox"));
            } catch (RuntimeException e) {
                steps.add(new Step("listMessages", false, describe(e)));
            }
        } else {
            steps.add(new Step("listMessages", false, "no folder with role inbox"));
        }

        try {
            MessagePage found = mail.search(user, "the", 0, 5);
            steps.add(new Step("search", true, found.total() + " hits for \"the\""));
        } catch (RuntimeException e) {
            steps.add(new Step("search", false, describe(e)));
        }

        try {
            List<Identity> identities = mail.listIdentities(user);
            steps.add(new Step("listIdentities", !identities.isEmpty(), identities.toString()));
        } catch (RuntimeException e) {
            steps.add(new Step("listIdentities", false, describe(e)));
        }

        if (page.messages().isEmpty()) {
            steps.add(new Step("getMessage", false, "inbox is empty, nothing to open"));
            return steps;
        }

        MessageSummary first = page.messages().get(0);
        try {
            MessageBody body = mail.getMessage(user, first.id());
            steps.add(new Step("getMessage", true, "subject=" + body.subject()
                    + " html=" + (body.hasHtml() ? body.html().length() + "b" : "none")
                    + " text=" + (body.text() == null ? "none" : body.text().length() + "b")
                    + " files=" + body.files().size()));
        } catch (RuntimeException e) {
            steps.add(new Step("getMessage", false, describe(e)));
        }

        try {
            mail.setKeyword(user, first.id(), "flagged", true);
            MessageBody flagged = mail.getMessage(user, first.id());
            mail.setKeyword(user, first.id(), "flagged", false);
            MessageBody cleared = mail.getMessage(user, first.id());
            steps.add(new Step("setKeyword", flagged.flagged() && !cleared.flagged(),
                    "flagged " + flagged.flagged() + " then " + cleared.flagged()));
        } catch (RuntimeException e) {
            steps.add(new Step("setKeyword", false, describe(e)));
        }

        return steps;
    }

    private static String describe(RuntimeException e) {
        if (e instanceof MailException m) {
            return m.getKind() + (m.getErrorType() == null ? "" : "/" + m.getErrorType()) + ": " + m.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
