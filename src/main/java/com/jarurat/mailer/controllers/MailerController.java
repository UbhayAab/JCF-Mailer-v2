package com.jarurat.mailer.controllers; // Ensure package matches your project

import com.jarurat.mailer.services.MailerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mailer")
public class MailerController {

    private final MailerService mailerService;

    public MailerController(MailerService mailerService) {
        this.mailerService = mailerService;
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
            // Process the bounce/complaint JSON and update DB
            mailerService.processSnsNotification(payload);
        }

        return ResponseEntity.ok("Webhook processed");
    }

    // NEW: Looks for the token parameter instead of email
    @GetMapping("/unsubscribe")
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        System.out.println("🛑 Unsubscribe link clicked for token: " + token);
        
        mailerService.unsubscribeContact(token);
        
        // Return a clean, polite message to the doctor's web browser
        String htmlResponse = "<html><body><h3>You have been successfully unsubscribed.</h3><p>You will no longer receive emails from Jarurat Care Foundation.</p></body></html>";
        
        return ResponseEntity.ok()
                .header("Content-Type", "text/html")
                .body(htmlResponse);
    }
}