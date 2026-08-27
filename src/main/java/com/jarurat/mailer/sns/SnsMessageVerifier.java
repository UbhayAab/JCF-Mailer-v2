package com.jarurat.mailer.sns;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Proves an SNS payload really came from Amazon and really came from our topic.
 *
 * The previous version of this webhook trusted the body. That was not a
 * theoretical weakness: a reviewer posted a hand written bounce for an invented
 * donor address, with no credentials at all, and watched it land permanently in
 * the suppression list. Anyone who can reach the URL can silence any recipient,
 * and the endpoint has to be public because that is where Amazon posts. So the
 * signature is the only thing standing between the internet and the suppression
 * list, and it is checked before a single field of the body is read.
 *
 * Three separate checks, all required:
 *   1. The signing certificate comes from an Amazon SNS host. Without this the
 *      SigningCertURL is an SSRF primitive AND lets an attacker nominate their
 *      own signing key, which makes the signature meaningless.
 *   2. The signature verifies against that certificate over the canonical string
 *      Amazon defines, which is field order sensitive and excludes anything not
 *      listed. Signing the raw body instead would let unsigned fields ride along.
 *   3. The TopicArn is one we configured. A valid Amazon signature only proves
 *      Amazon sent it, not that it concerns us: any AWS customer can create a
 *      topic and point it here.
 */
@Component
public class SnsMessageVerifier {

    /** Only Amazon's own SNS endpoints may serve us a signing certificate. */
    private static final Pattern CERT_HOST =
            Pattern.compile("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$");

    /** Field order is part of the signature. Amazon defines these lists; do not sort them. */
    private static final List<String> NOTIFICATION_FIELDS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> CONFIRMATION_FIELDS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // A redirect could walk us off an amazonaws.com host after we validated it.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Certificates rotate rarely and every message fetches one, so cache by URL. */
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();

    private final List<String> allowedTopics;

    public SnsMessageVerifier(@Value("${jarurat.sns.allowed-topic-arns:}") String allowed) {
        this.allowedTopics = allowed == null || allowed.isBlank()
                ? List.of()
                : List.of(allowed.split("\\s*,\\s*"));
    }

    /**
     * Returns the parsed message once it is proven authentic, or throws. Callers
     * must not touch the body before this returns.
     */
    public JsonNode verify(String body) {
        JsonNode msg;
        try {
            msg = json.readTree(body);
        } catch (RuntimeException e) {
            throw new SnsRejected("Body is not JSON.");
        }

        String type = text(msg, "Type");
        List<String> fields = switch (type) {
            case "Notification" -> NOTIFICATION_FIELDS;
            case "SubscriptionConfirmation", "UnsubscribeConfirmation" -> CONFIRMATION_FIELDS;
            default -> throw new SnsRejected("Unknown SNS message type: " + type);
        };

        // Topic first: it is free to check and rejects the whole class of
        // "genuine Amazon signature, someone else's topic" messages.
        String topic = text(msg, "TopicArn");
        if (allowedTopics.isEmpty()) {
            throw new SnsRejected("No SNS topic is configured, so nothing can be accepted.");
        }
        if (!allowedTopics.contains(topic)) {
            throw new SnsRejected("TopicArn is not one of ours: " + topic);
        }

        String signature = text(msg, "Signature");
        String version = text(msg, "SignatureVersion");
        String algorithm = switch (version) {
            case "1" -> "SHA1withRSA";
            case "2" -> "SHA256withRSA";
            default -> throw new SnsRejected("Unsupported SignatureVersion: " + version);
        };

        PublicKey key = signingKey(text(msg, "SigningCertURL"));
        byte[] canonical = canonicalise(msg, fields).getBytes(StandardCharsets.UTF_8);

        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(canonical);
            if (!verifier.verify(Base64.getDecoder().decode(signature))) {
                throw new SnsRejected("Signature does not match.");
            }
        } catch (SnsRejected e) {
            throw e;
        } catch (Exception e) {
            throw new SnsRejected("Signature could not be checked: " + e.getMessage());
        }
        return msg;
    }

    /**
     * Amazon signs "Field\nValue\n" for each field it defines, in their order,
     * skipping any that is absent. Subject is the usual absentee.
     */
    private static String canonicalise(JsonNode msg, List<String> fields) {
        StringBuilder out = new StringBuilder(512);
        for (String field : fields) {
            JsonNode value = msg.get(field);
            if (value == null || value.isNull()) continue;
            out.append(field).append('\n').append(value.asString()).append('\n');
        }
        return out.toString();
    }

    private PublicKey signingKey(String certUrl) {
        return keys.computeIfAbsent(certUrl, url -> {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (RuntimeException e) {
                throw new SnsRejected("SigningCertURL is not a URL.");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || !CERT_HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()) {
                throw new SnsRejected("SigningCertURL is not an Amazon SNS host: " + url);
            }
            try {
                HttpResponse<byte[]> res = http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) {
                    throw new SnsRejected("Signing certificate fetch returned " + res.statusCode());
                }
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(res.body()));
                cert.checkValidity();
                return cert.getPublicKey();
            } catch (SnsRejected e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SnsRejected("Interrupted fetching the signing certificate.");
            } catch (Exception e) {
                throw new SnsRejected("Signing certificate unusable: " + e.getMessage());
            }
        });
    }

    /** Confirms a subscription by calling back the URL Amazon signed for us. */
    public void confirmSubscription(String subscribeUrl) {
        URI uri = URI.create(subscribeUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !CERT_HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()) {
            throw new SnsRejected("SubscribeURL is not an Amazon SNS host.");
        }
        try {
            http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SnsRejected("Interrupted confirming the subscription.");
        } catch (Exception e) {
            throw new SnsRejected("Could not confirm the subscription: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw new SnsRejected("Missing field: " + field);
        return value.asString();
    }

    /** Rejected before anything was trusted. Always answer 403, never explain to the caller. */
    public static class SnsRejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SnsRejected(String message) {
            super(message);
        }
    }
}
