package com.jarurat.mailer.sns;

import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.services.AuditService;
import com.jarurat.mailer.services.SuppressionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/**
 * Receives SES delivery, bounce and complaint events from Amazon SNS.
 *
 * This endpoint has to be reachable by anyone, because Amazon posts to it from
 * addresses we cannot predict, and it cannot carry CSRF because Amazon does not
 * send our token. Everything therefore rests on SnsMessageVerifier, which proves
 * the payload was signed by Amazon and names one of our own topics before a
 * single field is read. An earlier version of this endpoint skipped that and a
 * reviewer used it to permanently suppress an address with an unauthenticated
 * POST, so the verification is not decoration.
 *
 * Failures answer 403 with no detail. A caller who is not Amazon learns only that
 * it did not work, never which check rejected them.
 */
@RestController
@RequestMapping("/api/sns")
public class SnsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SnsWebhookController.class);

    private final SnsMessageVerifier verifier;
    private final SuppressionService suppression;
    private final MessageLogService messageLog;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public SnsWebhookController(SnsMessageVerifier verifier,
                                SuppressionService suppression,
                                MessageLogService messageLog,
                                AuditService audit) {
        this.verifier = verifier;
        this.suppression = suppression;
        this.messageLog = messageLog;
        this.audit = audit;
    }

    @PostMapping("/ses-events")
    public ResponseEntity<String> receive(@RequestBody String body) {
        JsonNode envelope;
        try {
            envelope = verifier.verify(body);
        } catch (SnsMessageVerifier.SnsRejected e) {
            // Logged so a misconfigured topic is visible to us, but never echoed.
            log.warn("Rejected an SNS payload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Rejected");
        }

        String type = envelope.path("Type").asString("");
        if ("SubscriptionConfirmation".equals(type)) {
            verifier.confirmSubscription(envelope.path("SubscribeURL").asString(""));
            audit.record("SNS_SUBSCRIPTION_CONFIRMED", envelope.path("TopicArn").asString(""), "");
            log.info("Confirmed SNS subscription for {}", envelope.path("TopicArn").asString(""));
            return ResponseEntity.ok("Confirmed");
        }
        if (!"Notification".equals(type)) {
            return ResponseEntity.ok("Ignored");
        }

        String inner = envelope.path("Message").asString("");
        // SES posts a plain sentence, not JSON, when a topic is first wired up.
        if (inner.isBlank() || inner.charAt(0) != '{') {
            log.info("SES topic validation message: {}", inner);
            return ResponseEntity.ok("Accepted");
        }
        try {
            handle(json.readTree(inner));
        } catch (RuntimeException e) {
            // A malformed inner payload is Amazon's problem, not a reason to make
            // SNS retry forever. Record it and accept.
            log.warn("SES event body could not be handled: {}", e.toString());
        }
        return ResponseEntity.ok("Accepted");
    }

    /** Handles both the older notificationType shape and the newer eventType one. */
    private void handle(JsonNode event) {
        String kind = event.path("eventType").asString(event.path("notificationType").asString(""));
        JsonNode mail = event.path("mail");
        String from = bareAddress(mail.path("source").asString(""));

        switch (kind) {
            case "Bounce" -> {
                JsonNode bounce = event.path("bounce");
                boolean permanent = "Permanent".equals(bounce.path("bounceType").asString(""));
                for (JsonNode r : bounce.path("bouncedRecipients")) {
                    String email = bareAddress(r.path("emailAddress").asString(""));
                    if (email.isBlank()) continue;
                    String detail = r.path("diagnosticCode").asString(bounce.path("bounceSubType").asString(""));
                    // Only a permanent bounce suppresses. A full mailbox or a
                    // greylist is a transient condition and SES retries it itself;
                    // suppressing on those would quietly delete real subscribers.
                    if (permanent) suppression.suppress(email, "BOUNCE");
                    messageLog.recordObservedDelivery(new MessageLogService.DeliveryReport(
                            from, email, "", permanent ? 550 : 450, detail, LocalDateTime.now(), false));
                }
            }
            case "Complaint" -> {
                for (JsonNode r : event.path("complaint").path("complainedRecipients")) {
                    String email = bareAddress(r.path("emailAddress").asString(""));
                    if (email.isBlank()) continue;
                    suppression.suppress(email, "COMPLAINT");
                    messageLog.recordObservedDelivery(new MessageLogService.DeliveryReport(
                            from, email, "", 0, "Marked as spam by the recipient",
                            LocalDateTime.now(), false));
                }
            }
            case "Delivery" -> {
                JsonNode delivery = event.path("delivery");
                String host = delivery.path("reportingMTA").asString("");
                String raw = delivery.path("smtpResponse").asString("");
                // SES hands us the recipient's reply verbatim, status code included,
                // and the log line renders code and detail together. Passing both
                // through unchanged prints "250 250 2.0.0 OK".
                int code = leadingCode(raw);
                String detail = code > 0 ? raw.substring(String.valueOf(code).length()).trim() : raw;
                for (JsonNode r : delivery.path("recipients")) {
                    boolean matched = messageLog.applyDeliveryReport(new MessageLogService.DeliveryReport(
                            from, bareAddress(r.asString("")), host,
                            code > 0 ? code : 250, detail, LocalDateTime.now(), true));
                    // An unmatched delivery means we sent something we never logged.
                    // Worth seeing, but not worth inventing a second row for: the
                    // Stalwart delivery log already covers mail submitted outside
                    // the console, and a duplicate here reads as two sends.
                    if (!matched) {
                        log.info("SES Delivery matched no open row: from={} to={}", from, r.asString(""));
                    }
                }
            }
            default -> log.debug("Ignoring SES event type {}", kind);
        }
    }

    /**
     * Strips a display name, so "Jarurat Care Foundation" &lt;admin@jarurat.care&gt;
     * becomes admin@jarurat.care.
     *
     * SES reports mail.source as the full RFC 5322 address it was handed, display
     * name and all, while the message log stores the bare address. Comparing the
     * two directly is why every campaign delivery event failed to find its row and
     * the log sat on "SES accepted the message" forever.
     */
    static String bareAddress(String address) {
        if (address == null) return "";
        int open = address.lastIndexOf('<');
        int close = address.lastIndexOf('>');
        String bare = open >= 0 && close > open ? address.substring(open + 1, close) : address;
        return bare.trim();
    }

    /** The three digit status a receiving server puts at the front of its reply, or 0. */
    private static int leadingCode(String response) {
        if (response == null || response.length() < 3) return 0;
        for (int i = 0; i < 3; i++) {
            if (!Character.isDigit(response.charAt(i))) return 0;
        }
        if (response.length() > 3 && !Character.isWhitespace(response.charAt(3))) return 0;
        return Integer.parseInt(response.substring(0, 3));
    }
}
