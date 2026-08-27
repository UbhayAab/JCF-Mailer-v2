package com.jarurat.mailer.otp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The OTP endpoints another project calls.
 *
 *   POST /api/v1/otp/request   issue a code and email it
 *   POST /api/v1/otp/verify    check a code
 *   POST /api/v1/otp/resend    send a fresh code for the same request
 *   POST /api/v1/otp/redeem    spend the proof, server to server
 *
 * Authenticated by API key, exactly like the rest of /api/v1. There is deliberately
 * no CORS configuration anywhere on this path and there must never be one: an API key
 * in browser JavaScript is a published key, and this key can send DKIM-signed mail
 * from jarurat.care to any address in the world.
 *
 * Request always answers 202 with the same body shape, whatever happened. A real
 * address, an unknown one, a bounced one and one over its limit are indistinguishable
 * from outside, because a caller who can tell them apart can enumerate the user base
 * one request at a time.
 */
@RestController
@RequestMapping("/api/v1/otp")
public class OtpApi {

    private final OtpService otp;

    public OtpApi(OtpService otp) {
        this.otp = otp;
    }

    public record OtpRequest(String email, String purpose, String template, String format,
                              Integer ttlSeconds, Map<String, String> data) {}

    public record OtpVerify(String challengeId, String email, String purpose, String code) {}

    public record OtpResend(String challengeId, Map<String, String> data) {}

    public record OtpRedeem(String verificationToken) {}

    @PostMapping("/request")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_SEND')")
    public ResponseEntity<?> request(@RequestBody(required = false) OtpRequest body,
                                     HttpServletRequest http) {
        if (body == null || body.email() == null || body.email().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "OTP_MALFORMED", "\"email\" is required.", "email", null);
        }
        try {
            OtpService.Issued issued = otp.request(body.email(), body.purpose(), body.template(),
                    body.format(), body.ttlSeconds(), body.data(), keyName(http), clientIp(http));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "accepted");
            out.put("challengeId", issued.challengeId());
            out.put("expiresAt", iso(issued.expiresAt()));
            out.put("resendAvailableAt", iso(issued.resendAvailableAt()));
            out.put("codeLength", issued.codeLength());
            return noStore(HttpStatus.ACCEPTED).body(out);
        } catch (OtpService.OtpRateLimitException e) {
            return rateLimited(e);
        } catch (IllegalArgumentException e) {
            // Only a malformed address, an unknown purpose or a missing template
            // reaches here, and none of them says anything about whether an account
            // exists. Naming the right field matters: pointing an integrator at
            // "email" when their purpose was wrong sends them looking in the wrong place.
            String message = String.valueOf(e.getMessage());
            String field = message.startsWith("Purpose") ? "purpose"
                    : message.startsWith("No template") ? "template" : "email";
            return error(HttpStatus.BAD_REQUEST, "OTP_MALFORMED", message, field, null);
        }
    }

    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_SEND')")
    public ResponseEntity<?> verify(@RequestBody(required = false) OtpVerify body) {
        if (body == null || body.code() == null || body.code().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "OTP_MALFORMED", "\"code\" is required.", "code", null);
        }
        OtpService.VerifyResult result;
        try {
            result = otp.verify(body.challengeId(), body.email(), body.purpose(), body.code());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "OTP_MALFORMED", e.getMessage(), null, null);
        }

        if (result.verified()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "verified");
            out.put("email", result.email());
            out.put("purpose", result.purpose());
            out.put("verifiedAt", iso(LocalDateTime.now()));
            out.put("verificationToken", result.verificationToken());
            out.put("verificationTokenExpiresAt", iso(result.verificationTokenExpiresAt()));
            return noStore(HttpStatus.OK).body(out);
        }
        return failureResponse(result);
    }

    @PostMapping("/resend")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_SEND')")
    public ResponseEntity<?> resend(@RequestBody(required = false) OtpResend body,
                                    HttpServletRequest http) {
        if (body == null || body.challengeId() == null || body.challengeId().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "OTP_MALFORMED",
                    "\"challengeId\" is required.", "challengeId", null);
        }
        try {
            OtpService.Issued issued = otp.resend(body.challengeId(), body.data(), keyName(http));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "accepted");
            out.put("challengeId", issued.challengeId());
            out.put("expiresAt", iso(issued.expiresAt()));
            out.put("resendAvailableAt", iso(issued.resendAvailableAt()));
            out.put("codeLength", issued.codeLength());
            // Said plainly, because the user has two emails in front of them and needs
            // to know which one works.
            out.put("note", "A new code was sent. The previous one no longer works, and the "
                    + "original expiry still applies.");
            return noStore(HttpStatus.ACCEPTED).body(out);
        } catch (OtpService.OtpRateLimitException e) {
            return rateLimited(e);
        }
    }

    @PostMapping("/redeem")
    @PreAuthorize("hasAuthority('TRANSACTIONAL_SEND')")
    public ResponseEntity<?> redeem(@RequestBody(required = false) OtpRedeem body) {
        if (body == null || body.verificationToken() == null || body.verificationToken().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "OTP_MALFORMED",
                    "\"verificationToken\" is required.", "verificationToken", null);
        }
        OtpService.VerifyResult result = otp.redeem(body.verificationToken());
        if (!result.verified()) return failureResponse(result);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "redeemed");
        out.put("email", result.email());
        out.put("purpose", result.purpose());
        return noStore(HttpStatus.OK).body(out);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<?> failureResponse(OtpService.VerifyResult result) {
        HttpStatus status = switch (String.valueOf(result.errorCode())) {
            case "OTP_MALFORMED" -> HttpStatus.BAD_REQUEST;
            case "OTP_EXPIRED" -> HttpStatus.GONE;
            case "OTP_LOCKED" -> HttpStatus.LOCKED;
            default -> HttpStatus.UNAUTHORIZED;
        };
        return error(status, result.errorCode(), result.message(), null, result)
                ;
    }

    private ResponseEntity<?> rateLimited(OtpService.OtpRateLimitException e) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", e.getCode());
        detail.put("message", e.getMessage());
        detail.put("retryAfterSeconds", e.getRetryAfterSeconds());
        return noStore(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.getRetryAfterSeconds())))
                .body(Map.of("error", detail));
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message,
                                    String field, OtpService.VerifyResult result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        if (field != null) detail.put("field", field);
        if (result != null && result.attemptsRemaining() != null)
            detail.put("attemptsRemaining", result.attemptsRemaining());
        if (result != null && result.retryAfterSeconds() != null)
            detail.put("retryAfterSeconds", result.retryAfterSeconds());

        ResponseEntity.BodyBuilder builder = noStore(status);
        if (result != null && result.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, result.retryAfterSeconds())));
        }
        return builder.body(Map.of("error", detail));
    }

    /** A one time code must never sit in a proxy cache or a browser's history. */
    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache");
    }

    private static String keyName(HttpServletRequest request) {
        return Objects.toString(request.getAttribute("apiKeyName"), "unknown");
    }

    /** nginx terminates TLS, so the socket address is always its loopback address. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return request.getRemoteAddr();
        int comma = forwarded.indexOf(',');
        return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }

    private static String iso(LocalDateTime when) {
        return when == null ? null : when.atOffset(ZoneOffset.UTC).toString();
    }
}
