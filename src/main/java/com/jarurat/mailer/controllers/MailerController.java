package com.jarurat.mailer.controllers;

import com.jarurat.mailer.services.MailerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mailer")
public class MailerController {

    private final MailerService mailerService;

    public MailerController(MailerService mailerService) {
        this.mailerService = mailerService;
    }

    // NEW: Endpoint to receive the CSV file
    @PostMapping("/contacts/upload")
    public ResponseEntity<String> uploadContacts(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please upload a valid CSV file.");
        }
        
        try {
            System.out.println("📂 Receiving CSV file: " + file.getOriginalFilename());
            mailerService.uploadContactsFromCsv(file);
            return ResponseEntity.ok("CSV Uploaded and Contacts Saved Successfully!");
        } catch (Exception e) {
            System.err.println("❌ CSV Upload Failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Failed to upload: " + e.getMessage());
        }
    }

    @GetMapping("/trigger-campaign")
    public ResponseEntity<String> triggerCampaign() {
        System.out.println("🌐 Web request received! Triggering campaign...");
        mailerService.runCampaignSimulation();
        return ResponseEntity.ok("Campaign triggered successfully! Check your terminal.");
    }

    @PostMapping("/sns-webhook")
    public ResponseEntity<String> handleSnsWebhook(
            @RequestHeader(value = "x-amz-sns-message-type", required = false) String messageType,
            @RequestBody String payload) {

        System.out.println("🚨 AWS SNS WEBHOOK HIT!");
        
        if ("SubscriptionConfirmation".equals(messageType)) {
            System.out.println("AWS Subscription Confirmation Payload:");
            System.out.println(payload);
        } 
        else if ("Notification".equals(messageType)) {
            mailerService.processSnsNotification(payload);
        }

        return ResponseEntity.ok("Webhook processed");
    }

    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        System.out.println("🛑 Unsubscribe link clicked for token: " + token);
        
        mailerService.unsubscribeContact(token);
        
        String htmlResponse = "<html><body><h3>You have been successfully unsubscribed.</h3><p>You will no longer receive emails from Jarurat Care Foundation.</p></body></html>";
        
        return ResponseEntity.ok()
                .header("Content-Type", "text/html")
                .body(htmlResponse);
    }
}