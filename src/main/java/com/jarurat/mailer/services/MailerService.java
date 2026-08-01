package com.jarurat.mailer.services;

import com.jarurat.mailer.models.Contact;
import com.jarurat.mailer.models.DeliveryLog;
import com.jarurat.mailer.repositories.ContactRepository;
import com.jarurat.mailer.repositories.DeliveryLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MailerService {

    private final ContactRepository contactRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final SesV2Client sesClient;
    private final String fromEmail;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Virtual Thread Executor for high-throughput concurrency
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MailerService(
            ContactRepository contactRepository, 
            DeliveryLogRepository deliveryLogRepository,
            @Value("${aws.region}") String regionString,
            @Value("${aws.ses.fromEmail}") String fromEmail) {
            
        this.contactRepository = contactRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.fromEmail = fromEmail;

        // Automatically uses the EC2 IAM Role!
        this.sesClient = SesV2Client.builder()
                .region(Region.of(regionString))
                .build();
    }

    public void runCampaignSimulation() {
        List<Contact> cleanContacts = contactRepository.findByStatus("CLEAN");
        System.out.println("🚀 Preparing to push " + cleanContacts.size() + " emails to AWS SES...");

        for (Contact contact : cleanContacts) {
            virtualThreadExecutor.submit(() -> {
                try {
                    sendEmailViaSes(contact);
                } catch (Exception e) {
                    System.err.println("❌ Failed to send to " + contact.getEmail() + ": " + e.getMessage());
                }
            });
        }
    }

    private void sendEmailViaSes(Contact contact) {
        // Construct the Unsubscribe Link using the secure UUID Token
        String unsubscribeLink = "http://<ELASTIC_IP>/api/mailer/unsubscribe?token=" + contact.getUnsubscribeToken();

        // Build the Email Content with the footer
        String emailBodyText = "Dear " + contact.getName() + ",\n\n"
                + "Thank you for registering for the Horizon Summit. Your secure webinar link is attached.\n\n"
                + "Best,\nJarurat Care Foundation\n\n"
                + "--------------------------------------------------\n"
                + "If you no longer wish to receive these updates, click here to unsubscribe:\n"
                + unsubscribeLink;

        Content subject = Content.builder().data("Registration Confirmed: Horizon Oncology Summit 2026").build();
        Content body = Content.builder().data(emailBodyText).build();
        Body messageBody = Body.builder().text(body).build();
        Message message = Message.builder().subject(subject).body(messageBody).build();

        // Specify the Recipient
        Destination destination = Destination.builder().toAddresses(contact.getEmail()).build();
        EmailContent emailContent = EmailContent.builder().simple(message).build();

        // Build the AWS API Request
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(destination)
                .content(emailContent)
                .build();

        // Fire the request to AWS
        SendEmailResponse response = sesClient.sendEmail(request);

        // Log the successful dispatch in the database
        DeliveryLog log = new DeliveryLog(contact.getEmail(), "SENT_SES_MESSAGE_ID: " + response.messageId(), LocalDateTime.now());
        deliveryLogRepository.save(log);
        
        System.out.println("✅ AWS SES Dispatched: " + contact.getEmail() + " | Thread: " + Thread.currentThread());
    }

    // Handles JSON payload from AWS SNS Bounces & Complaints
    public void processSnsNotification(String payload) {
        try {
            JsonNode rootNode = objectMapper.readTree(payload);
            
            if (!rootNode.has("Message")) {
                return;
            }

            // AWS SNS wraps the SES event inside a stringified JSON field called 'Message'
            String messageString = rootNode.get("Message").asText();
            JsonNode sesNode = objectMapper.readTree(messageString);

            String notificationType = sesNode.path("notificationType").asText();

            if ("Bounce".equalsIgnoreCase(notificationType)) {
                JsonNode bouncedRecipients = sesNode.path("bounce").path("bouncedRecipients");
                for (JsonNode recipient : bouncedRecipients) {
                    String email = recipient.path("emailAddress").asText();
                    suppressContact(email, "BOUNCE");
                }
            } else if ("Complaint".equalsIgnoreCase(notificationType)) {
                JsonNode complainedRecipients = sesNode.path("complaint").path("complainedRecipients");
                for (JsonNode recipient : complainedRecipients) {
                    String email = recipient.path("emailAddress").asText();
                    suppressContact(email, "SPAM_COMPLAINT");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error parsing SNS payload: " + e.getMessage());
        }
    }

    private void suppressContact(String email, String reason) {
        contactRepository.findByEmail(email).ifPresentOrElse(contact -> {
            contact.setStatus("SUPPRESSED");
            contactRepository.save(contact);
            System.out.println("🚨 CONTACT SUPPRESSED: " + email + " | Reason: " + reason);
        }, () -> {
            System.out.println("⚠️ Bounce/Complaint received for untracked email: " + email);
        });
    }

    // NEW: Now accepts a secure token instead of an email address
    public void unsubscribeContact(String token) {
        contactRepository.findByUnsubscribeToken(token).ifPresentOrElse(contact -> {
            contact.setStatus("UNSUBSCRIBED"); // Using a distinct status from BOUNCE
            contactRepository.save(contact);
            System.out.println("✅ DATABASE UPDATED: " + contact.getEmail() + " is now UNSUBSCRIBED.");
        }, () -> {
            System.out.println("⚠️ Unsubscribe requested for unknown token: " + token);
        });
    }
}