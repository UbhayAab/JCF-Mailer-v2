package com.jarurat.mailer.controllers;

import com.jarurat.mailer.analytics.OpenTrackingService;
import com.jarurat.mailer.services.SuppressionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Base64;

/**
 * Endpoints recipients hit from their inbox. Unauthenticated by necessity, so
 * everything here is read-mostly and validates its own input.
 */
@RestController
@RequestMapping("/api/mailer")
public class TrackingController {

    private static final byte[] PIXEL = Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    private final OpenTrackingService tracking;
    private final SuppressionService suppression;

    public TrackingController(OpenTrackingService tracking, SuppressionService suppression) {
        this.tracking = tracking;
        this.suppression = suppression;
    }

    @GetMapping("/click")
    public void click(@RequestParam("token") String token,
                      @RequestParam("url") String url,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        // Without this check the tracker is an open redirect that would happily
        // bounce a recipient to javascript: or data:.
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported link");
            return;
        }
        // The classifier decides whether this counts as engagement; the redirect
        // happens either way, so a misread never breaks a recipient's link.
        String target = url;
        try {
            target = tracking.recordClick(token, url, request.getHeader("User-Agent"), clientIp(request));
        } catch (Exception ignored) {}
        response.sendRedirect(target);
    }

    @GetMapping("/open")
    public ResponseEntity<byte[]> open(@RequestParam("token") String token, HttpServletRequest request) {
        // Goes through the classifier so a scanner pre-fetch is stored but not
        // counted as a human read.
        try {
            tracking.recordOpen(token, request.getHeader("User-Agent"), clientIp(request));
        } catch (Exception ignored) {}
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(PIXEL);
    }

    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        String email = suppression.unsubscribeByToken(token);
        return page("You are unsubscribed", email == null
                ? "This link has already been used, or it has expired."
                : email + " will not receive further campaigns from Jarurat Care Foundation.");
    }

    /** Gmail and Yahoo one-click unsubscribe POST to the same URL. */
    @PostMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribePost(@RequestParam("token") String token) {
        suppression.unsubscribeByToken(token);
        return ResponseEntity.ok("Unsubscribed");
    }

    @GetMapping("/success")
    public ResponseEntity<String> success() {
        return page("Thanks for registering", "We will contact you with further updates.");
    }

    /*
     * There was a POST /sns-webhook here. It was public, CSRF exempt, and verified
     * nothing: no SNS signature check and no TopicArn allowlist, so anyone on the
     * internet could permanently suppress any address by posting a hand written
     * bounce, and later rewrite message log rows with a fabricated SMTP reply.
     *
     * Deleted rather than signed, because nothing legitimate ever called it. This
     * account has no SNS topic and the IAM user cannot create one (SNS:CreateTopic
     * is denied), so no genuine SES notification can arrive. Bounces and complaints
     * come from SuppressionService.syncFromSes, which reads SES's own suppression
     * list every 15 minutes and is authoritative. Real delivery replies come from
     * StalwartDeliveryLog, which reads this box's own mail log.
     */

    /** nginx terminates TLS, so the socket address is always its loopback address. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded;
    }

    private ResponseEntity<String> page(String heading, String body) {
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>%s</title></head>
                <body style="margin:0;background:#070b18;color:#e8ecf8;font-family:system-ui,Segoe UI,Arial,sans-serif">
                <div style="max-width:520px;margin:14vh auto;padding:40px;text-align:center;
                            background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.09);border-radius:18px">
                  <h2 style="margin:0 0 12px;font-size:23px">%s</h2>
                  <p style="margin:0;color:#a2acc8;font-size:15px;line-height:1.65">%s</p>
                </div></body></html>""".formatted(heading, heading, body);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }
}
