package com.jarurat.mailer.controllers;

import com.jarurat.mailer.services.MailerService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/mailer")
public class MailerController {

    private final MailerService mailerService;

    public MailerController(MailerService mailerService) { this.mailerService = mailerService; }

    @GetMapping("/click")
    public void handleLinkClick(@RequestParam("token") String token, @RequestParam("url") String url, HttpServletResponse response) throws IOException {
        String targetUrl = mailerService.trackClickAndGetUrl(token, url);
        response.sendRedirect(targetUrl);
    }

    // NEW: The universal "Thanks for Registering" page
    @GetMapping("/success")
    public ResponseEntity<String> registrationSuccess() {
        String html = "<html><body style='text-align:center; padding:50px; font-family:Arial, sans-serif; background-color:#f8f9fa;'>" +
                      "<h2 style='color:#2c3e50;'>Thanks for Registering!</h2>" +
                      "<p style='color:#7f8c8d; font-size:18px;'>We will contact you for further updates.</p>" +
                      "</body></html>";
        return ResponseEntity.ok().header("Content-Type", "text/html").body(html);
    }

    @PostMapping("/contacts/upload")
    public ResponseEntity<String> uploadContacts(@RequestParam("file") MultipartFile file, @RequestParam("campaignName") String campaignName) {
        try {
            mailerService.uploadContactsFromCsv(file, campaignName);
            return ResponseEntity.ok("CSV Uploaded successfully to " + campaignName);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
        }
    }

    @PostMapping("/trigger-campaign")
    public ResponseEntity<String> triggerCampaign(@RequestParam("campaignName") String campaignName) {
        mailerService.runCampaign(campaignName);
        return ResponseEntity.ok("Campaign " + campaignName + " triggered!");
    }

    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        mailerService.unsubscribeContact(token);
        return ResponseEntity.ok().header("Content-Type", "text/html").body("<h3 style='text-align:center; margin-top:50px; font-family:sans-serif;'>Successfully unsubscribed.</h3>");
    }

    @PostMapping("/sns-webhook")
    public ResponseEntity<String> handleSnsWebhook(@RequestHeader(value = "x-amz-sns-message-type", required = false) String type, @RequestBody String payload) {
        if ("Notification".equals(type)) mailerService.processSnsNotification(payload);
        return ResponseEntity.ok("Webhook processed");
    }
}