package com.jarurat.mailer.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Transport for Stalwart's JMAP endpoint. Knows about sessions, authentication,
 * the request envelope and blob download. Knows nothing about mail: that is
 * MailService's job.
 *
 * Two things about this server are measured facts rather than assumptions, and
 * both shape the code below:
 *   - The documented POST /api management path does not exist in 0.16.17. Every
 *     call, management or mail, goes to POST /jmap/.
 *   - Stalwart advertises apiUrl, downloadUrl and uploadUrl as absolute URLs on
 *     host mail.jarurat.care, which does not resolve on the box itself. We keep
 *     the paths it gives us and put our own host in front.
 */
@Component
public class JmapClient {

    public static final String CORE = "urn:ietf:params:jmap:core";
    public static final String MAIL = "urn:ietf:params:jmap:mail";
    public static final String SUBMISSION = "urn:ietf:params:jmap:submission";

    private static final String DEFAULT_DOWNLOAD = "/jmap/download/{accountId}/{blobId}/{name}?accept={type}";
    private static final String DEFAULT_UPLOAD = "/jmap/upload/{accountId}/";

    private final MailCredentialStore credentials;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    private final URI sessionUri;
    /** scheme://host[:port], the prefix we splice onto every path Stalwart advertises. */
    private final String origin;
    private final Duration requestTimeout;

    /** accountId never changes for a user, so one session document per user is enough. */
    private final Map<String, MailSession> sessions = new ConcurrentHashMap<>();

    public JmapClient(MailCredentialStore credentials,
                      @Value("${jarurat.mail.jmap-url:https://127.0.0.1/jmap/}") String jmapUrl,
                      @Value("${jarurat.mail.connect-timeout-seconds:5}") int connectSeconds,
                      @Value("${jarurat.mail.request-timeout-seconds:20}") int requestSeconds) {
        this.credentials = credentials;

        String base = jmapUrl.endsWith("/") ? jmapUrl : jmapUrl + "/";
        URI baseUri = canonicalise(URI.create(base));
        this.origin = baseUri.getScheme() + "://" + baseUri.getHost()
                + (baseUri.getPort() < 0 ? "" : ":" + baseUri.getPort());
        this.sessionUri = URI.create(origin + baseUri.getPath() + "session");
        this.requestTimeout = Duration.ofSeconds(Math.max(1, requestSeconds));

        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectSeconds)))
                // A redirect would re-send the Authorization header to wherever it points.
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(trustContextFor(baseUri))
                // The whole app runs on virtual threads, so a blocking exchange parks
                // a virtual thread rather than holding a platform one. This executor
                // keeps the client's own internal work on virtual threads too.
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    // ------------------------------------------------------------------
    // TLS
    // ------------------------------------------------------------------

    /**
     * Stalwart's self-signed certificate carries exactly one SAN, DNS:localhost
     * (measured). Java's HttpClient always performs endpoint identification and
     * offers no supported way to switch it off, so https://127.0.0.1/ fails the
     * handshake even with a trust manager that accepts the certificate. It is the
     * same interface and the same server, so rewrite the host rather than making
     * whoever sets the property discover this the hard way.
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
     * Stalwart serves its JMAP endpoint with an rcgen self-signed certificate
     * (measured: subject "CN = rcgen self signed cert", the only SAN is
     * DNS:localhost). Nothing in the JVM trust store will ever validate that, so
     * for the loopback hop we install a trust manager that accepts it.
     *
     * Why that is safe here: Campaign Studio and Stalwart run on the same EC2
     * instance and this connection never leaves the loopback interface. Anyone
     * positioned to intercept traffic on 127.0.0.1 is already root on the box and
     * owns both ends of the conversation, so certificate validation is protecting
     * against an attacker who has already won. The trust manager below re-checks
     * that the peer really is loopback on every handshake, so this context cannot
     * be reused for an off-box connection by accident.
     *
     * What has to change if the mail server ever moves off this box: nothing in
     * this method, deliberately. Point jarurat.mail.jmap-url at the remote host
     * and the isLoopback test below fails, so the client silently falls back to
     * ordinary PKIX validation against the JVM trust store and will refuse the
     * self-signed certificate. At that point Stalwart needs a real certificate
     * (Let's Encrypt for mail.jarurat.care), which is the correct answer for a
     * connection crossing a network anyway.
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
    // Session
    // ------------------------------------------------------------------

    /**
     * Cached per user. The account id is opaque and cannot be derived from the address.
     *
     * Deliberately not computeIfAbsent, and this is not a style choice. That method
     * runs its mapping function inside the map's bin lock, and on Java 21 a virtual
     * thread that blocks inside a synchronized block pins its carrier thread. The
     * app runs every request on a virtual thread and the box has two vCPUs, so two
     * concurrent cache misses fetching two different sessions pin both carriers and
     * nothing can ever be scheduled to complete them. Measured: five concurrent
     * listFolders calls wedged permanently here, with two threads parked in
     * parkOnCarrierThread inside computeIfAbsent, until this was changed.
     *
     * Fetching outside the lock lets two racing threads fetch the same session
     * twice. That costs one extra GET and both answers are identical, which is a
     * far better trade than a deadlock.
     */
    public MailSession session(String user) {
        String key = key(user);
        MailSession cached = sessions.get(key);
        if (cached != null) return cached;

        MailSession fetched = fetchSession(user);
        MailSession raced = sessions.putIfAbsent(key, fetched);
        return raced != null ? raced : fetched;
    }

    /** Drop the cached session, e.g. after a credential change. */
    public void forgetSession(String user) {
        sessions.remove(key(user));
    }

    /**
     * Checks a candidate secret straight against Stalwart, without consulting or
     * touching the credential store.
     *
     * Opening a mailbox used to write the secret into the process-wide store first
     * and verify it afterwards, which opened two holes at once: for the length of
     * one round trip an unverified password authenticated every other request in
     * the process, and a colleague's typo evicted a working credential. Building
     * the header from the argument closes both, because nothing is stored until
     * the answer comes back.
     */
    public boolean probe(String user, String secret) {
        if (user == null || secret == null || secret.isEmpty()) return false;

        String basic = Base64.getEncoder().encodeToString(
                (key(user) + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(sessionUri)
                .timeout(requestTimeout)
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json")
                .GET().build();
        try {
            int status = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).statusCode();
            if (status == 401 || status == 403) return false;
            if (status < 200 || status >= 300) {
                throw new MailException(MailException.Kind.PROTOCOL,
                        "Mail server answered " + status + " while checking the password.");
            }
            return true;
        } catch (IOException e) {
            throw new MailException(MailException.Kind.TRANSPORT, "Could not reach the mail server.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailException(MailException.Kind.TRANSPORT, "Interrupted by shutdown.", e);
        }
    }

    private MailSession fetchSession(String user) {
        HttpResponse<String> res = exchange(authorised(user, sessionUri).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = parse(res.body());

        String accountId = text(root.path("primaryAccounts"), MAIL);
        if (accountId == null || accountId.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "Session document for " + user + " names no primary mail account");
        }

        return new MailSession(
                text(root, "username") == null ? user : text(root, "username"),
                accountId,
                URI.create(rebase(text(root, "apiUrl"), sessionUri.getPath().replace("/session", "/"))),
                rebase(text(root, "downloadUrl"), DEFAULT_DOWNLOAD),
                rebase(text(root, "uploadUrl"), DEFAULT_UPLOAD),
                text(root, "state"));
    }

    /**
     * Keeps the path Stalwart advertises and swaps in our own origin. String
     * surgery rather than URI parsing because the download and upload templates
     * contain {accountId} style placeholders, and braces are not legal in a URI.
     */
    private String rebase(String advertised, String fallbackPath) {
        if (advertised == null || advertised.isBlank()) return origin + fallbackPath;
        int schemeEnd = advertised.indexOf("//");
        int pathStart = schemeEnd < 0 ? -1 : advertised.indexOf('/', schemeEnd + 2);
        return pathStart < 0 ? origin + fallbackPath : origin + advertised.substring(pathStart);
    }

    // ------------------------------------------------------------------
    // Method calls
    // ------------------------------------------------------------------

    public ObjectNode newObject() { return json.createObjectNode(); }

    public ArrayNode newArray() { return json.createArrayNode(); }

    /** One [name, args, callId] triple for the methodCalls array. */
    public ArrayNode invocation(String name, ObjectNode args, String callId) {
        ArrayNode call = json.createArrayNode();
        call.add(name);
        call.add(args);
        call.add(callId);
        return call;
    }

    /** Args pre-filled with the account id, which every mail method requires. */
    public ObjectNode accountArgs(String user) {
        ObjectNode args = json.createObjectNode();
        args.put("accountId", session(user).accountId());
        return args;
    }

    /** Sends one JMAP request and returns the methodResponses array. */
    public JsonNode call(String user, List<String> using, ArrayNode methodCalls) {
        ObjectNode body = json.createObjectNode();
        ArrayNode urns = body.putArray("using");
        for (String urn : using) urns.add(urn);
        body.set("methodCalls", methodCalls);

        HttpResponse<String> res = exchange(
                authorised(user, session(user).apiUri())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonNode responses = parse(res.body()).path("methodResponses");
        if (!responses.isArray()) {
            throw new MailException(MailException.Kind.PROTOCOL, "JMAP reply carried no methodResponses");
        }
        return responses;
    }

    /**
     * Pulls one method's arguments out of a methodResponses array.
     *
     * Matching on the name as well as the call id is not optional. When a request
     * uses onSuccessUpdateEmail, Stalwart appends an extra Email/set response that
     * reuses the submission's call id, so a lookup by id alone returns the wrong
     * object. Measured on this server: a send comes back as
     * [Email/set c1][EmailSubmission/set s1][Email/set s1].
     */
    public JsonNode response(JsonNode methodResponses, String method, String callId) {
        for (JsonNode entry : methodResponses) {
            if (entry.size() < 3) continue;
            String name = string(entry.path(0));
            if (!callId.equals(string(entry.path(2)))) continue;
            if ("error".equals(name)) throw methodError(entry.path(1), method);
            if (method.equals(name)) return entry.path(1);
        }
        throw new MailException(MailException.Kind.PROTOCOL,
                "Mail server sent no " + method + " response for call " + callId);
    }

    private MailException methodError(JsonNode args, String method) {
        String type = text(args, "type");
        String description = text(args, "description");
        MailException.Kind kind = switch (type == null ? "" : type) {
            case "unknownMethod", "unknownCapability", "invalidArguments", "invalidResultReference"
                    -> MailException.Kind.PROTOCOL;
            case "accountNotFound", "accountNotSupportedByMethod" -> MailException.Kind.NOT_FOUND;
            case "forbidden" -> MailException.Kind.AUTH;
            default -> MailException.Kind.METHOD;
        };
        return new MailException(kind, type,
                method + " failed: " + (type == null ? "unknown error" : type)
                        + (description == null ? "" : " (" + description + ")"), null);
    }

    // ------------------------------------------------------------------
    // Blobs
    // ------------------------------------------------------------------

    /** Fetches attachment bytes. Only called when a user actually clicks a file. */
    public byte[] download(String user, String blobId, String name, String type) {
        MailSession s = session(user);
        String url = s.downloadTemplate()
                .replace("{accountId}", segment(s.accountId()))
                .replace("{blobId}", segment(blobId))
                .replace("{name}", segment(name == null || name.isBlank() ? "attachment" : name))
                .replace("{type}", enc(type == null || type.isBlank() ? "application/octet-stream" : type));

        return exchange(authorised(user, URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private HttpRequest.Builder authorised(String user, URI uri) {
        String secret = credentials.secretFor(user).orElseThrow(() -> new MailException(
                MailException.Kind.AUTH, "No mail credential held for " + user + ". Sign in to mail again."));

        // The one line that changes on the day MailCredentialStore starts handing
        // out OAuth access tokens instead: "Bearer " + secret.
        String basic = Base64.getEncoder().encodeToString(
                (user + ":" + secret).getBytes(StandardCharsets.UTF_8));

        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Basic " + basic)
                .header("Accept", "application/json");
    }

    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> res = http.send(request, handler);
            int code = res.statusCode();
            if (code == 401 || code == 403) {
                throw new MailException(MailException.Kind.AUTH,
                        "Stalwart rejected the mailbox credentials (HTTP " + code + ")");
            }
            if (code == 404) {
                throw new MailException(MailException.Kind.NOT_FOUND,
                        "Mail server has nothing at " + request.uri().getPath());
            }
            if (code / 100 != 2) {
                throw new MailException(MailException.Kind.TRANSPORT,
                        "Mail server returned HTTP " + code + snippet(res.body()));
            }
            return res;
        } catch (SSLException e) {
            // Worth its own message: this is what an operator sees the day the mail
            // server stops being loopback and its self-signed certificate stops
            // being acceptable. "Cannot reach" would send them hunting a firewall.
            throw new MailException(MailException.Kind.TRANSPORT,
                    "TLS to the mail server at " + request.uri() + " failed (" + e.getMessage()
                            + "). Only loopback connections may use Stalwart's self-signed certificate; "
                            + "an off-box mail server needs a real one.", e);
        } catch (IOException e) {
            throw new MailException(MailException.Kind.TRANSPORT,
                    "Cannot reach the mail server at " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailException(MailException.Kind.TRANSPORT,
                    "Interrupted while waiting for the mail server", e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (RuntimeException e) {
            // Jackson 3 throws unchecked, so this is the only catch that can see it.
            throw new MailException(MailException.Kind.PROTOCOL,
                    "Mail server sent something that is not JSON" + snippet(body), e);
        }
    }

    // ------------------------------------------------------------------
    // JSON helpers, shared with MailService
    // ------------------------------------------------------------------

    /**
     * Reads a string field, treating absent, null and non-string alike as null.
     * Jackson 3's asString() would happily hand back "null" for a JSON null.
     */
    public static String text(JsonNode node, String field) {
        return string(node.path(field));
    }

    public static String string(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    /** For query values, where form encoding is what the server expects. */
    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /**
     * For path segments. URLEncoder writes a space as "+", which is only correct in
     * a query string: in a path a "+" is a literal plus, so an attachment called
     * "donor report.csv" would be fetched as "donor+report.csv" and not found.
     */
    private static String segment(String raw) {
        return enc(raw).replace("+", "%20");
    }

    private static String snippet(Object body) {
        if (!(body instanceof String s) || s.isBlank()) return "";
        return ": " + (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }

    private static String key(String user) {
        return user == null ? "" : user.trim().toLowerCase(Locale.ROOT);
    }

    /** Only here so the self-test can report what it is pointed at. */
    public URI sessionUri() { return sessionUri; }
}
