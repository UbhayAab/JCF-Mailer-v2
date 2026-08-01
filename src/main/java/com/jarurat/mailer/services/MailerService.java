package com.jarurat.mailer.services;

import com.jarurat.mailer.models.Contact;
import com.jarurat.mailer.models.DeliveryLog;
import com.jarurat.mailer.models.Template;
import com.jarurat.mailer.repositories.ContactRepository;
import com.jarurat.mailer.repositories.DeliveryLogRepository;
import com.jarurat.mailer.repositories.TemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MailerService {

    private final ContactRepository contactRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final TemplateRepository templateRepository;
    private final SesV2Client sesClient;
    private final String fromEmail;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MailerService(
            ContactRepository contactRepository, 
            DeliveryLogRepository deliveryLogRepository,
            TemplateRepository templateRepository,
            @Value("${aws.region}") String regionString,
            @Value("${aws.ses.fromEmail}") String fromEmail) {
            
        this.contactRepository = contactRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.templateRepository = templateRepository;
        this.fromEmail = fromEmail;

        this.sesClient = SesV2Client.builder()
                .region(Region.of(regionString))
                .build();
    }

    public void uploadContactsFromCsv(MultipartFile file) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean isFirstLine = true;
            while ((line = br.readLine()) != null) {
                if (isFirstLine) { isFirstLine = false; continue; }
                String[] data = line.split(",");
                if (data.length >= 2) {
                    String name = data[0].trim();
                    String email = data[1].trim();
                    if (!contactRepository.existsByEmail(email)) {
                        contactRepository.save(new Contact(email, name, "CLEAN"));
                    }
                }
            }
        }
    }

    public void runCampaignSimulation() {
        // Fetch the active template from DB
        Template template = templateRepository.findById(1L)
            .orElseThrow(() -> new RuntimeException("No template found! Please save one in the dashboard."));

        List<Contact> cleanContacts = contactRepository.findByStatus("CLEAN");
        System.out.println("🚀 Preparing to push " + cleanContacts.size() + " emails to AWS SES...");

        for (Contact contact : cleanContacts) {
            virtualThreadExecutor.submit(() -> {
                try {
                    sendEmailViaSes(contact, template);
                } catch (Exception e) {
                    System.err.println("❌ Failed to send to " + contact.getEmail() + ": " + e.getMessage());
                }
            });
        }
    }

    private void sendEmailViaSes(Contact contact, Template template) {
        String unsubscribeLink = "http://<ELASTIC_IP>/api/mailer/unsubscribe?token=" + contact.getUnsubscribeToken();

        // Dynamically replace tags in the HTML
        String personalizedHtml = template.getHtmlBody()
                .replace("{{NAME}}", contact.getName())
                .replace("{{UNSUBSCRIBE_LINK}}", unsubscribeLink);

        Content subject = Content.builder().data(template.getSubject()).build();
        Content body = Content.builder().data(personalizedHtml).build();
        
        // UPDATED: Now building as HTML instead of Text!
        Body messageBody = Body.builder().html(body).build(); 
        Message message = Message.builder().subject(subject).body(messageBody).build();

        Destination destination = Destination.builder().toAddresses(contact.getEmail()).build();
        EmailContent emailContent = EmailContent.builder().simple(message).build();

        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(destination)
                .content(emailContent)
                .build();

        SendEmailResponse response = sesClient.sendEmail(request);

        DeliveryLog log = new DeliveryLog(contact.getEmail(), "SENT", LocalDateTime.now());
        deliveryLogRepository.save(log);
        
        System.out.println("✅ AWS SES Dispatched: " + contact.getEmail());
    }

    public void processSnsNotification(String payload) {
        try {
            JsonNode rootNode = objectMapper.readTree(payload);
            if (!rootNode.has("Message")) return;

            String messageString = rootNode.get("Message").asText();
            JsonNode sesNode = objectMapper.readTree(messageString);
            String notificationType = sesNode.path("notificationType").asText();

            if ("Bounce".equalsIgnoreCase(notificationType)) {
                JsonNode bouncedRecipients = sesNode.path("bounce").path("bouncedRecipients");
                for (JsonNode recipient : bouncedRecipients) {
                    suppressContact(recipient.path("emailAddress").asText(), "BOUNCE");
                }
            } else if ("Complaint".equalsIgnoreCase(notificationType)) {
                JsonNode complainedRecipients = sesNode.path("complaint").path("complainedRecipients");
                for (JsonNode recipient : complainedRecipients) {
                    suppressContact(recipient.path("emailAddress").asText(), "SPAM_COMPLAINT");
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Error parsing SNS payload: " + e.getMessage());
        }
    }

    private void suppressContact(String email, String reason) {
        List<Contact> contacts = contactRepository.findByEmail(email);
        if (!contacts.isEmpty()) {
            for (Contact contact : contacts) {
                contact.setStatus("SUPPRESSED");
                contactRepository.save(contact);
            }
            // Log suppression in Delivery Logs too!
            deliveryLogRepository.save(new DeliveryLog(email, "SUPPRESSED (" + reason + ")", LocalDateTime.now()));
        }
    }

    public void unsubscribeContact(String token) {
        contactRepository.findByUnsubscribeToken(token).ifPresentOrElse(contact -> {
            contact.setStatus("UNSUBSCRIBED"); 
            contactRepository.save(contact);
            deliveryLogRepository.save(new DeliveryLog(contact.getEmail(), "UNSUBSCRIBED", LocalDateTime.now()));
        }, () -> {});
    }
}