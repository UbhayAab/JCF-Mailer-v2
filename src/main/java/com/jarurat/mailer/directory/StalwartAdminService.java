package com.jarurat.mailer.directory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Creates, edits and removes mailboxes on the Stalwart server, so adding a
 * colleague never means opening a shell on the box.
 *
 * Everything here is a measured fact about Stalwart 0.16.17 rather than a reading
 * of any documentation, because the documentation describes a server that no
 * longer exists:
 *   - The REST management API was removed in 0.16. There is no POST /api/principal
 *     any more; directory administration is JMAP and only JMAP.
 *   - The object is x:Account. Principal/set is documented, is implied by the
 *     principals capability, and answers HTTP 400. Do not reach for it.
 *   - Authentication is a bearer token from /auth/token, not the basic auth the
 *     mail side uses. A mailbox password cannot administer the directory.
 *
 * Deliberately separate from JmapClient. That one authenticates as a mailbox on
 * behalf of whoever is signed in; this one authenticates as the server
 * administrator and answers to nobody's session. Folding them together would put
 * an admin credential one bug away from every webmail request.
 */
@Service
public class StalwartAdminService {

    /**
     * The nine capabilities every directory request must advertise, in full, every
     * time.
     *
     * This is not a wish list and trimming it is not tidying up. Stalwart validates
     * the using array before it looks at a single method call, and any subset -
     * including the core-plus-principals pair that looks like the only relevant one
     * - is answered with HTTP 400 notRequest and the whole request is discarded.
     * Measured against the live server: writes only go through when all nine are
     * present, calendars and vacationresponse included, however little they have to
     * do with creating a mailbox.
     */
    private static final List<String> ADMIN_CAPS = List.of(
            "urn:ietf:params:jmap:core",
            "urn:stalwart:jmap",
            "urn:ietf:params:jmap:blob",
            "urn:ietf:params:jmap:mail",
            "urn:ietf:params:jmap:calendars",
            "urn:ietf:params:jmap:contacts",
            "urn:ietf:params:jmap:principals",
            "urn:ietf:params:jmap:sieve",
            "urn:ietf:params:jmap:vacationresponse");

    private static final String GET = "x:Account/get";
    private static final String SET = "x:Account/set";

    /** Creation id for the single account a create carries. Echoed back in created/notCreated. */
    private static final String NEW = "new-0";

    /**
     * Lowercase only, and no case folding on the way in. The name is the directory
     * key Stalwart derives the address from, not a display field, so quietly
     * accepting "HR" and storing "hr" would hand back an id under a name the
     * operator never typed. Refusing costs one retype and never surprises.
     */
    private static final Pattern LOCAL_PART = Pattern.compile("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$");

    /** Stalwart account ids are short opaque strings ("e", "b"). Nothing else is one. */
    private static final Pattern ACCOUNT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int MIN_PASSWORD = 12;
    private static final long DEFAULT_QUOTA = 2L * 1024 * 1024 * 1024;

    /** Mint ahead of expiry by this much, so a call never starts on a token that dies mid-flight. */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    /**
     * Fixed rather than configurable. This is a loopback hop to a process on the
     * same box doing a directory write, so there is no network to be slow and no
     * knob an operator would ever have a reason to turn.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    private final URI jmapUri;
    private final URI tokenUri;
    private final String refreshToken;
    private final String accountId;
    private final String domainId;
    private final String domain;

    /**
     * The live access token, or null when none has been minted yet.
     *
     * Volatile plus a lock rather than synchronized, and that is a correctness
     * choice rather than a style one. Every request in this app runs on a virtual
     * thread, and on Java 21 a virtual thread that blocks inside a synchronized
     * block pins its carrier; the box has two vCPUs, so two operators hitting a
     * cold cache at once would park both carriers on the same token fetch with
     * nothing left to schedule the completion. JmapClient hit exactly that and
     * records the measurement. ReentrantLock parks the virtual thread instead.
     */
    private volatile AccessToken token;
    private final ReentrantLock tokenLock = new ReentrantLock();

    public StalwartAdminService(
            @Value("${jarurat.mail.admin.jmap-url:https://127.0.0.1:8443/jmap/}") String jmapUrl,
            @Value("${jarurat.mail.admin.token-url:https://127.0.0.1:8443/auth/token}") String tokenUrl,
            @Value("${jarurat.mail.admin.refresh-token:}") String refreshToken,
            @Value("${jarurat.mail.admin.account-id:d333333}") String accountId,
            @Value("${jarurat.mail.admin.domain-id:b}") String domainId,
            @Value("${jarurat.mail.domain:jarurat.care}") String domain) {

        this.jmapUri = canonicalise(URI.create(jmapUrl));
        this.tokenUri = canonicalise(URI.create(tokenUrl));
        this.refreshToken = refreshToken == null ? "" : refreshToken.trim();
        this.accountId = accountId == null ? "" : accountId.trim();
        this.domainId = domainId == null ? "" : domainId.trim();
        this.domain = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);

        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // A redirect would re-send the bearer token to wherever it points.
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(trustContextFor(this.jmapUri))
                // The whole app runs on virtual threads, so a blocking exchange parks
                // a virtual thread rather than holding a platform one.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * False when no refresh token is configured, which is the normal state on a
     * developer machine and on any box that is not running Stalwart.
     *
     * This exists so the screen can say "mailbox administration is not set up here"
     * in one sentence. Without it the first thing an operator sees is a failed
     * token fetch dressed up as a mail server outage, and they go and restart the
     * mail server.
     */
    public boolean configured() {
        return !refreshToken.isEmpty();
    }

    /** The one domain this console administers. Every name and alias below lives on it. */
    public String domain() {
        return domain;
    }

    /**
     * Every account the directory holds, ordered by address.
     *
     * The role is carried through from the server's @type because x:Account/get
     * returns more than user mailboxes: groups, lists and the domain row live in
     * the same list, and a screen that showed them all as mailboxes would invite
     * somebody to set a password on a domain.
     *
     * Returns empty rather than throwing when unconfigured, so a caller can render
     * a directory screen without a special case around every read.
     */
    public List<MailboxSummary> list() {
        if (!configured()) return List.of();

        JsonNode body = call(GET, accountArgs(), "g");
        List<MailboxSummary> rows = new ArrayList<>();
        for (JsonNode account : body.path("list")) rows.add(summary(account));
        rows.sort(Comparator.comparing(MailboxSummary::emailAddress, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(rows);
    }

    /**
     * Creates a mailbox and returns its new id.
     *
     * emailAddress is deliberately not sent. The server derives it from name plus
     * domainId, and supplying our own would give the one field a mailbox is
     * actually identified by two sources of truth that can disagree.
     */
    public String create(String localPart, String description, String password, long quotaBytes) {
        requireConfigured();
        String name = requireLocalPart(localPart);
        requirePassword(password);
        requireFree(name);

        ObjectNode account = json.createObjectNode();
        account.put("@type", "User");
        account.put("name", name);
        account.put("domainId", domainId);
        account.set("credentials", credentials(password));
        account.set("roles", typed("User"));
        account.set("permissions", typed("Inherit"));
        ObjectNode quotas = json.createObjectNode();
        quotas.put("maxDiskQuota", quotaBytes <= 0 ? DEFAULT_QUOTA : quotaBytes);
        account.set("quotas", quotas);
        account.put("description", description == null ? "" : description.trim());
        account.put("locale", "en_US");
        account.set("encryptionAtRest", typed("Disabled"));

        ObjectNode args = accountArgs();
        args.putObject("create").set(NEW, account);

        JsonNode body = call(SET, args, "s");
        JsonNode refused = body.path("notCreated").path(NEW);
        if (!refused.isMissingNode() && !refused.isNull()) {
            // carriesSecret: this request contained the password, so the server's own
            // prose about why it refused goes nowhere near the reply.
            throw rejected("Could not create " + qualify(name), refused, true);
        }

        String id = text(body.path("created").path(NEW), "id");
        if (id == null || id.isBlank()) {
            throw upstream("Stalwart accepted the new mailbox but named no id for it.");
        }
        return id;
    }

    /**
     * Sets a mailbox password, in a request that changes nothing else. Ever.
     *
     * Stalwart validates an x:Account patch as a single unit: one unacceptable
     * value anywhere in it rejects the whole patch with invalidPatch and leaves
     * every other field untouched. Bundling a password change with an alias change
     * therefore produces the worst outcome on offer, a call that looks like it
     * mostly worked while the password silently did not change, and the operator
     * walks away and hands out a credential that was never set. Learned the hard
     * way. setPassword and setAliases are two round trips on purpose, and any
     * future "save everything" convenience must still send them apart.
     */
    public void setPassword(String id, String password) {
        requireConfigured();
        requirePassword(password);

        ObjectNode patch = json.createObjectNode();
        patch.set("credentials", credentials(password));
        update(id, patch, "p", "change that mailbox password", true);
    }

    /**
     * Replaces the whole alias list on a mailbox.
     *
     * Replaces, not merges, because that is what the server does with this field:
     * the list sent is the list kept. A caller meaning to add one alias has to send
     * the existing ones back with it, and a caller sending an empty list removes
     * them all.
     */
    public void setAliases(String id, List<String> aliasLocalParts) {
        requireConfigured();

        // An objectList: keyed by position rather than an array, and each entry is a
        // bare name plus a domainId. Sending "abuse@jarurat.care" as the name is
        // accepted and quietly produces an alias for abuse@jarurat.care@jarurat.care.
        ObjectNode aliases = json.createObjectNode();
        List<String> seen = new ArrayList<>();
        int slot = 0;
        for (String raw : aliasLocalParts == null ? List.<String>of() : aliasLocalParts) {
            String name = requireLocalPart(stripDomain(raw));
            // list() hands out full addresses, so a screen that round trips them will
            // send duplicates the moment somebody edits one entry. Dropping them is
            // kinder than an error about a list the operator did not write.
            if (seen.contains(name)) continue;
            seen.add(name);

            ObjectNode alias = json.createObjectNode();
            alias.put("name", name);
            alias.put("domainId", domainId);
            aliases.set(String.valueOf(slot++), alias);
        }

        ObjectNode patch = json.createObjectNode();
        patch.set("aliases", aliases);
        update(id, patch, "a", "set the aliases on that mailbox", false);
    }

    /** Destroys a mailbox and everything in it. There is no undo on the server side. */
    public void delete(String id) {
        requireConfigured();
        String target = requireId(id);

        ObjectNode args = accountArgs();
        args.putArray("destroy").add(target);

        JsonNode body = call(SET, args, "d");
        JsonNode refused = body.path("notDestroyed").path(target);
        if (!refused.isMissingNode() && !refused.isNull()) {
            throw rejected("Could not delete that mailbox", refused, false);
        }
        JsonNode destroyed = body.path("destroyed");
        if (destroyed.isArray() && !contains(destroyed, target)) {
            throw badRequest("There is no mailbox with id " + target + ".");
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private MailboxSummary summary(JsonNode account) {
        String name = nz(text(account, "name"));
        String address = text(account, "emailAddress");
        return new MailboxSummary(
                nz(text(account, "id")),
                name,
                address == null || address.isBlank() ? qualify(name) : address,
                nz(text(account, "description")),
                aliases(account.path("aliases")),
                account.path("quotas").path("maxDiskQuota").asLong(0L),
                account.path("usedDiskQuota").asLong(0L),
                nz(text(account, "createdAt")),
                nz(text(account, "@type")));
    }

    /**
     * Only the values matter here, so iterating the object is enough and the "0",
     * "1", ... keys can be ignored. They matter a great deal on the way out: see
     * setAliases.
     */
    private List<String> aliases(JsonNode node) {
        if (!node.isObject()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode alias : node) {
            String full = text(alias, "emailAddress");
            if (full == null || full.isBlank()) {
                String name = text(alias, "name");
                if (name == null || name.isBlank()) continue;
                // The server sends a domainId, not a domain. This console administers
                // exactly one domain, so an alias on any other is not ours to render.
                full = qualify(name);
            }
            out.add(full);
        }
        return List.copyOf(out);
    }

    private String qualify(String name) {
        return name.isEmpty() ? "" : name + "@" + domain;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /**
     * Every write starts here. 503 rather than 400: nothing the caller typed is
     * wrong, the box simply has no admin credential, and only an operator with
     * shell access can fix it.
     */
    private void requireConfigured() {
        if (configured()) return;
        throw new StalwartAdminException(503,
                "Mailbox administration is not configured on this server. "
                        + "Set STALWART_REFRESH_TOKEN and restart.");
    }

    private static String requireLocalPart(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (!LOCAL_PART.matcher(name).matches()) {
            throw badRequest("\"" + name + "\" is not a usable mailbox name. Use lowercase letters, "
                    + "digits, dot, underscore or hyphen, starting and ending with a letter or digit.");
        }
        return name;
    }

    /**
     * Length only. No character class rule and no upper bound on purpose: Stalwart
     * stores whatever it is handed, and a complexity rule here would push people
     * towards a shorter password that satisfies it rather than a longer one that
     * does not have to.
     */
    private static void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw badRequest("Choose a mailbox password of at least " + MIN_PASSWORD + " characters.");
        }
    }

    private static String requireId(String id) {
        String v = id == null ? "" : id.trim();
        if (!ACCOUNT_ID.matcher(v).matches()) throw badRequest("That is not a mailbox id.");
        return v;
    }

    /**
     * One extra read before a create rather than letting the server refuse it.
     *
     * Stalwart's own answer to a duplicate is an invalidProperties naming "name",
     * which tells an operator nothing about which mailbox already holds it.
     * Mailboxes get created a handful of times a year, so the round trip is free
     * and the sentence is worth having.
     */
    private void requireFree(String name) {
        for (MailboxSummary existing : list()) {
            if (name.equalsIgnoreCase(existing.localPart())) {
                throw badRequest(qualify(name) + " already exists.");
            }
        }
    }

    /** Accepts either "abuse" or "abuse@jarurat.care", and refuses anybody else's domain. */
    private String stripDomain(String raw) {
        String v = raw == null ? "" : raw.trim();
        int at = v.indexOf('@');
        if (at < 0) return v;
        String host = v.substring(at + 1).toLowerCase(Locale.ROOT);
        if (!host.equals(domain)) {
            throw badRequest("Aliases can only be created on " + domain + ", not on " + host + ".");
        }
        return v.substring(0, at);
    }

    // ------------------------------------------------------------------
    // JMAP
    // ------------------------------------------------------------------

    private ObjectNode accountArgs() {
        ObjectNode args = json.createObjectNode();
        args.put("accountId", accountId);
        return args;
    }

    private ObjectNode typed(String type) {
        ObjectNode node = json.createObjectNode();
        node.put("@type", type);
        return node;
    }

    private ObjectNode credentials(String password) {
        ObjectNode credential = json.createObjectNode();
        credential.put("@type", "Password");
        credential.put("secret", password);
        ObjectNode credentials = json.createObjectNode();
        credentials.set("0", credential);
        return credentials;
    }

    /**
     * Applies one patch to one account.
     *
     * carriesSecret marks a patch whose body contained a password. When one of those
     * is refused, the server's own description of why is dropped and only the error
     * type survives into the message: the one thing that must never reach a screen,
     * a log line or an audit row is the value we just tried to set.
     */
    private void update(String id, ObjectNode patch, String callId, String what, boolean carriesSecret) {
        String target = requireId(id);

        ObjectNode args = accountArgs();
        args.putObject("update").set(target, patch);

        JsonNode body = call(SET, args, callId);
        JsonNode refused = body.path("notUpdated").path(target);
        if (!refused.isMissingNode() && !refused.isNull()) {
            throw rejected("Could not " + what, refused, carriesSecret);
        }
        // A set that names the id in neither map has not touched it, which on this
        // server means no such account. Only checked when updated is really an
        // object, so a server that omits the map entirely is not called a liar.
        JsonNode updated = body.path("updated");
        if (updated.isObject() && !updated.has(target)) {
            throw badRequest("There is no mailbox with id " + target + ".");
        }
    }

    /** Sends one JMAP request carrying one method call, and returns that call's arguments. */
    private JsonNode call(String method, ObjectNode args, String callId) {
        ObjectNode envelope = json.createObjectNode();
        ArrayNode using = envelope.putArray("using");
        for (String urn : ADMIN_CAPS) using.add(urn);
        ArrayNode invocation = json.createArrayNode();
        invocation.add(method);
        invocation.add(args);
        invocation.add(callId);
        envelope.putArray("methodCalls").add(invocation);

        HttpRequest request = HttpRequest.newBuilder(jmapUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(envelope), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = send(request, "the mail server");
        int code = res.statusCode();
        if (code == 401 || code == 403) {
            // Drop the cached token so the next call mints a fresh one. No automatic
            // retry: this method also carries creates, and re-sending a write whose
            // outcome is unknown is how one mailbox becomes two.
            token = null;
            throw upstream("Stalwart rejected the administration token (HTTP " + code
                    + "). It may have been revoked; re-issue it from the webadmin console.");
        }
        if (code / 100 != 2) {
            throw upstream("Stalwart answered HTTP " + code + " to " + method + problem(res.body()));
        }

        JsonNode responses = parse(res.body()).path("methodResponses");
        if (!responses.isArray()) throw upstream("Stalwart's reply carried no methodResponses.");

        for (JsonNode entry : responses) {
            if (entry.size() < 3 || !callId.equals(string(entry.path(2)))) continue;
            String name = string(entry.path(0));
            // A method level failure arrives as ["error", {...}, callId] inside an
            // otherwise perfectly successful HTTP 200. Reading entry[1] as the
            // method's own result here would silently treat a refusal as a success.
            if ("error".equals(name)) {
                throw upstream(method + " failed: " + nz(text(entry.path(1), "type")));
            }
            if (method.equals(name)) return entry.path(1);
        }
        throw upstream("Stalwart sent no " + method + " response.");
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    private String accessToken() {
        AccessToken cached = token;
        if (fresh(cached)) return cached.value();

        tokenLock.lock();
        try {
            // Re-read inside the lock: whoever held it first has almost certainly
            // already minted the token this thread was about to ask for.
            cached = token;
            if (fresh(cached)) return cached.value();
            AccessToken minted = mint();
            token = minted;
            return minted.value();
        } finally {
            tokenLock.unlock();
        }
    }

    private static boolean fresh(AccessToken t) {
        return t != null && Instant.now().plus(REFRESH_SKEW).isBefore(t.expiresAt());
    }

    private AccessToken mint() {
        String form = "grant_type=refresh_token&client_id=webadmin&refresh_token="
                + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res = send(request, "the mail server's token endpoint");
        if (res.statusCode() / 100 != 2) {
            // Nothing from the body goes into this message. A token endpoint answers
            // with tokens, and a half successful response would put one in an
            // operator's log for ever.
            throw upstream("Stalwart refused the administration refresh token (HTTP " + res.statusCode()
                    + "). Re-issue it from the webadmin console and update STALWART_REFRESH_TOKEN.");
        }

        JsonNode body = parse(res.body());
        String value = text(body, "access_token");
        if (value == null || value.isBlank()) {
            throw upstream("The token endpoint answered without an access_token.");
        }
        // Floored above the refresh skew. A lifetime shorter than the skew would make
        // fresh() permanently false, so every single call would mint a token and then
        // decide it was already too old to use.
        long ttl = Math.max(2 * REFRESH_SKEW.getSeconds(), body.path("expires_in").asLong(3600L));
        return new AccessToken(value, Instant.now().plusSeconds(ttl));
    }

    /** An access token and the instant it stops being usable. Never logged, never rendered. */
    private record AccessToken(String value, Instant expiresAt) { }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private HttpResponse<String> send(HttpRequest request, String what) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (SSLException e) {
            // Worth its own sentence: this is what an operator sees the day the mail
            // server stops being loopback. "Cannot reach" would send them hunting a
            // firewall rule that is not the problem.
            throw upstream("TLS to " + what + " failed (" + e.getMessage()
                    + "). Only loopback connections may use Stalwart's self-signed certificate; "
                    + "an off-box mail server needs a real one.");
        } catch (IOException e) {
            throw upstream("Cannot reach " + what + " at " + request.uri().getHost() + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw upstream("Interrupted while waiting for " + what + ".");
        }
    }

    /**
     * The error type out of a failure body, and nothing else.
     *
     * The raw body is never echoed, deliberately. Stalwart quotes the arguments it
     * did not like back at you, and on this endpoint one of those arguments is a
     * mailbox password. A truncated snippet would be more useful nine times out of
     * ten and unforgivable the tenth.
     */
    private String problem(String body) {
        if (body == null || body.isBlank()) return ".";
        try {
            String type = text(parse(body), "type");
            return type == null || type.isBlank() ? "." : " (" + type + ").";
        } catch (RuntimeException e) {
            return ".";
        }
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            // Jackson 3 throws unchecked, so this is the only catch that can see it.
            throw upstream("Stalwart sent something that is not JSON.");
        }
    }

    // ------------------------------------------------------------------
    // TLS
    // ------------------------------------------------------------------

    /**
     * Stalwart's self-signed certificate carries exactly one SAN, DNS:localhost
     * (measured). Java's HttpClient always performs endpoint identification and
     * offers no supported way to switch it off, so https://127.0.0.1:8443/ fails
     * the handshake even with a trust manager that accepts the certificate. Same
     * interface, same server, so rewrite the host rather than making whoever sets
     * the property discover this the hard way.
     */
    private static URI canonicalise(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) return uri;
        if (!isLoopbackHost(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())) return uri;
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), "localhost", uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    /**
     * The same loopback-only relaxation JmapClient uses, and the comment there is
     * the long version of this one. In short: Stalwart serves an rcgen self-signed
     * certificate that nothing in the JVM trust store will ever validate, this
     * connection never leaves the loopback interface, and anyone positioned to
     * intercept traffic on 127.0.0.1 is already root on the box and owns both ends
     * of the conversation.
     *
     * The trust manager re-checks that the peer really is loopback on every
     * handshake, so this context cannot be reused for an off-box connection by
     * accident. Point jarurat.mail.admin.jmap-url at a remote host and the test
     * below fails, the client falls back to ordinary PKIX validation against the
     * JVM trust store, and the self-signed certificate is refused - which is the
     * right answer for a connection crossing a network. It lives here rather than
     * being shared with JmapClient only because that class keeps it private; the
     * day a third caller needs it, lift it into one place.
     */
    private static SSLContext trustContextFor(URI base) {
        boolean https = "https".equalsIgnoreCase(base.getScheme());
        if (!https || !isLoopbackHost(base.getHost())) {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException("No default SSL context available", e);
            }
        }

        TrustManager loopbackOnly = new X509ExtendedTrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                requireLoopback(socket == null ? null : socket.getInetAddress(), null);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                requireLoopback(null, engine == null ? null : engine.getPeerHost());
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                // No peer information at all, so there is no way to prove this is loopback.
                throw new CertificateException("Refusing a server certificate with no peer context");
            }

            // We are never a TLS server, so nothing may present a client certificate to us.
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                throw new CertificateException("This client never accepts client certificates");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("This client never accepts client certificates");
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("This client never accepts client certificates");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{loopbackOnly}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the loopback TLS context", e);
        }
    }

    private static void requireLoopback(InetAddress peer, String peerHost) throws CertificateException {
        if (peer != null && peer.isLoopbackAddress()) return;
        if (peerHost != null && isLoopbackHost(peerHost)) return;
        throw new CertificateException("Self-signed certificate offered by a non-loopback peer: "
                + (peer != null ? peer.getHostAddress() : String.valueOf(peerHost)));
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    /**
     * One type for everything this service refuses or cannot do, carrying the
     * sentence an operator should read and the status that sentence deserves.
     *
     * The status rides on the exception rather than being fixed at the controller
     * because two failures that look identical from outside must not read the same:
     * "that name is already taken" is the caller's to fix, "Stalwart did not
     * answer" is not, and returning 400 for a dead mail server sends whoever is on
     * support hunting a typo that does not exist.
     *
     * Nothing built here ever carries a password, an access token or the refresh
     * token in its message. Every call site that could is routed through the
     * carriesSecret path in update() or drops the server's prose entirely.
     */
    public static class StalwartAdminException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        public StalwartAdminException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** 400 when the caller can fix it, 502 when Stalwart cannot, 503 when nothing is configured. */
        public int status() { return status; }
    }

    private static StalwartAdminException badRequest(String message) {
        return new StalwartAdminException(400, message);
    }

    private static StalwartAdminException upstream(String message) {
        return new StalwartAdminException(502, message);
    }

    /**
     * A set that refused one object. These are nearly always about a value the
     * caller supplied, so they read as 400 rather than as a mail server fault.
     */
    private static StalwartAdminException rejected(String what, JsonNode error, boolean carriesSecret) {
        String type = text(error, "type");
        StringBuilder message = new StringBuilder(what).append(": ")
                .append(type == null || type.isBlank() ? "the server refused it without saying why" : type);
        if (!carriesSecret) {
            String detail = text(error, "description");
            if (detail != null && !detail.isBlank()) message.append(" (").append(detail).append(')');
        }
        if ("invalidPatch".equals(type)) {
            message.append(". Stalwart validates a patch as a whole, so nothing in that request was applied.");
        }
        return new StalwartAdminException(400, message.toString());
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode entry : array) {
            if (value.equals(string(entry))) return true;
        }
        return false;
    }

    /** Absent, null and non-string all read as null. Jackson 3's asString() would hand back "null". */
    private static String text(JsonNode node, String field) {
        return string(node.path(field));
    }

    private static String string(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * One mailbox as a console needs it: addresses already assembled, quotas already
     * in bytes, and the server's @type carried through as role so a domain row is
     * never mistaken for a person.
     */
    public record MailboxSummary(String id,
                                 String localPart,
                                 String emailAddress,
                                 String description,
                                 List<String> aliases,
                                 long quotaBytes,
                                 long usedBytes,
                                 String createdAt,
                                 String role) {

        public MailboxSummary {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    // ------------------------------------------------------------------
    // Domains
    // ------------------------------------------------------------------

    /**
     * Same accountId, same nine capabilities, same envelope as x:Account: only the
     * object name is different. That is why this rides the plumbing above instead
     * of growing a second client next to it.
     */
    private static final String DOMAIN_GET = "x:Domain/get";

    /**
     * Every domain the directory serves, ordered by name.
     *
     * Read far more defensively than list() is, deliberately. x:Domain is not the
     * object this console was built around, the catalogue on the server carries
     * fields nothing here has ever seen, and this feeds a read-only health screen:
     * a server that omits a field, renames one or answers in a shape nobody
     * expected should cost a blank on one card, not an exception on the page.
     *
     * Empty rather than an exception when unconfigured, matching list(), so the
     * caller can fall back to the configured domain without a special case for
     * every box that is not running Stalwart.
     */
    public List<DomainSummary> domains() {
        if (!configured()) return List.of();

        JsonNode body = call(DOMAIN_GET, accountArgs(), "dg");
        List<DomainSummary> rows = new ArrayList<>();
        for (JsonNode entry : body.path("list")) {
            String name = nz(text(entry, "name")).trim().toLowerCase(Locale.ROOT);
            // Lowercased on the way out because everything downstream compares it
            // against jarurat.mail.domain, which is lowercased in the constructor.
            // A nameless row is neither renderable nor addressable, so it is dropped.
            if (name.isEmpty()) continue;
            rows.add(new DomainSummary(
                    name,
                    strings(entry.path("aliases")),
                    nz(text(entry, "catchAllAddress")),
                    entry.path("allowRelaying").asBoolean(false),
                    nz(text(entry, "createdAt"))));
        }
        rows.sort(Comparator.comparing(DomainSummary::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(rows);
    }

    /**
     * Reads a field holding several strings without insisting on how the server
     * spelled it.
     *
     * Three encodings for the same idea are already in play on this API: a plain
     * JSON array, an object keyed by the values themselves with true for a value
     * (JMAP's own Set wire form), and the "0", "1", ... objectList that x:Account
     * aliases arrive in. Betting on one and losing means the field silently reads
     * as empty and a screen reports zero aliases on a domain that has four, so
     * accept all three and let the server pick.
     */
    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode value : node) add(out, string(value));
        } else if (node.isObject()) {
            for (var field : node.properties()) {
                JsonNode value = field.getValue();
                if (value.isBoolean()) {
                    if (value.asBoolean(false)) add(out, field.getKey());
                } else if (value.isString()) {
                    add(out, string(value));
                } else if (value.isObject()) {
                    add(out, text(value, "name"));
                }
            }
        }
        return List.copyOf(out);
    }

    private static void add(List<String> out, String value) {
        if (value != null && !value.isBlank()) out.add(value.trim());
    }

    /**
     * One domain as a health screen needs it.
     *
     * aliases here are alternate domain names, not mailbox aliases. The two read
     * the same on a card and are nothing alike: one is another name for the whole
     * domain, the other is another address for one person. Counting mailbox
     * aliases is the caller's job and comes out of list().
     */
    public record DomainSummary(String name,
                                List<String> aliases,
                                String catchAllAddress,
                                boolean allowRelaying,
                                String createdAt) {

        public DomainSummary {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
}
