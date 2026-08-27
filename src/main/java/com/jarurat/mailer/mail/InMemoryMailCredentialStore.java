package com.jarurat.mailer.mail;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heap only, and deliberately so. Persisting mailbox secrets to Postgres would
 * turn one database backup into a set of working mail credentials, so a restart
 * is allowed to cost every user one re-authentication instead.
 *
 * See MailCredentialStore for why this whole layer is temporary.
 */
@Component
public class InMemoryMailCredentialStore implements MailCredentialStore {

    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public Optional<String> secretFor(String user) {
        return Optional.ofNullable(secrets.get(key(user)));
    }

    @Override
    public void remember(String user, String secret) {
        if (user == null || user.isBlank() || secret == null || secret.isEmpty()) return;
        secrets.put(key(user), secret);
    }

    @Override
    public void forget(String user) {
        secrets.remove(key(user));
    }

    @Override
    public boolean knows(String user) {
        return secrets.containsKey(key(user));
    }

    @Override
    public Set<String> knownUsers() {
        return Set.copyOf(secrets.keySet());
    }

    /** Stalwart usernames are the addresses, and addresses are case insensitive. */
    private static String key(String user) {
        return user == null ? "" : user.trim().toLowerCase();
    }
}
