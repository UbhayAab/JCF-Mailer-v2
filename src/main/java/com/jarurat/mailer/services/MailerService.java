package com.jarurat.mailer.services;

import com.jarurat.mailer.models.*;
import com.jarurat.mailer.models.Contact;
import com.jarurat.mailer.repositories.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MailerService {

    private final ContactRepository contactRepository;
    private final CampaignRepository campaignRepository;
    private final GlobalSuppressionRepository globalSuppressionRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final SesV2Client sesClient;
    private final String fromEmail;
    private final String appDomain; 
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MailerService(ContactRepository contactRepository, CampaignRepository campaignRepository,
                         GlobalSuppressionRepository globalSuppressionRepository, DeliveryLogRepository deliveryLogRepository,
                         @Value("${aws.region}") String regionString, @Value("${aws.ses.fromEmail}") String fromEmail,
                         @Value("${app.domain:http://<ELASTIC_IP>}") String appDomain) {
        this.contactRepository = contactRepository; this.campaignRepository = campaignRepository; this.globalSuppressionRepository = globalSuppressionRepository; this.deliveryLogRepository = deliveryLogRepository;
        this.fromEmail = fromEmail;
        this.appDomain = appDomain;
        this.sesClient = SesV2Client.builder().region(Region.of(regionString)).build();
    }

    public void uploadContactsFromCsv(MultipartFile file, String campaignName) throws Exception {
        System.out.println("📂 Starting CSV processing for: " + campaignName + "...");
        int addedCount = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line; boolean isFirstLine = true;
            while ((line = br.readLine()) != null) {
                if (isFirstLine) { isFirstLine = false; continue; }
                String[] data = line.split(",");
                if (data.length >= 2) {
                    String name = data[0].trim(); String email = data[1].trim();
                    if (!globalSuppressionRepository.existsById(email) && !contactRepository.existsByCampaignNameAndEmail(campaignName, email)) {
                        contactRepository.save(new Contact(campaignName, email, name, "CLEAN"));
                        addedCount++;
                    }
                }
            }
        }
        System.out.println("✅ CSV Complete | Added: " + addedCount);
    }

    public void runCampaign(String campaignName) {
        Campaign campaign = campaignRepository.findById(campaignName).orElseThrow(() -> new RuntimeException("Campaign not found!"));
        List<Contact> cleanContacts = contactRepository.findByCampaignNameAndStatus(campaignName, "CLEAN");
        
        // RESTORED: Terminal log to prove the trigger was hit!
        System.out.println("🚀 Triggering " + campaignName + " - pushing " + cleanContacts.size() + " emails...");
        
        for (Contact contact : cleanContacts) {
            virtualThreadExecutor.submit(() -> {
                try {
                    sendEmailViaSes(contact, campaign);
                    contact.setStatus("SENT");
                    contactRepository.save(contact);
                } catch (Exception e) { 
                    System.err.println("❌ Failed: " + contact.getEmail() + " - " + e.getMessage()); 
                }
            });
        }
    }

    private void sendEmailViaSes(Contact contact, Campaign campaign) {
        String unsubscribeLink = appDomain + "/api/mailer/unsubscribe?token=" + contact.getUnsubscribeToken();
        String html = campaign.getHtmlBody().replace("{{NAME}}", contact.getName()).replace("{{UNSUBSCRIBE_LINK}}", unsubscribeLink);

        StringBuffer sb = new StringBuffer();
        Matcher m = Pattern.compile("\\{\\{TRACK:(.*?)\\}\\}").matcher(html);
        while (m.find()) {
            try {
                String originalUrl = m.group(1);
                String encodedUrl = java.net.URLEncoder.encode(originalUrl, java.nio.charset.StandardCharsets.UTF_8.name());
                String trackLink = appDomain + "/api/mailer/click?token=" + contact.getUnsubscribeToken() + "&url=" + encodedUrl;
                m.appendReplacement(sb, trackLink);
            } catch (Exception e) {}
        }
        m.appendTail(sb);

        Message message = Message.builder().subject(Content.builder().data(campaign.getSubject()).build())
                .body(Body.builder().html(Content.builder().data(sb.toString()).build()).build()).build();

        sesClient.sendEmail(SendEmailRequest.builder().fromEmailAddress(fromEmail)
                .destination(Destination.builder().toAddresses(contact.getEmail()).build())
                .content(EmailContent.builder().simple(message).build()).build());

        deliveryLogRepository.save(new DeliveryLog(contact.getEmail(), "SENT (" + campaign.getName() + ")", LocalDateTime.now()));
        
        // RESTORED: Terminal log to prove SES actually dispatched it!
        System.out.println("✅ AWS SES Dispatched: " + contact.getEmail());
    }

    public String trackClickAndGetUrl(String token, String targetUrl) {
        contactRepository.findByUnsubscribeToken(token).ifPresent(contact -> {
            contact.setClickedUrl(targetUrl);
            contactRepository.save(contact);
            System.out.println("🎯 CLICK TRACKED: " + contact.getEmail());
        });
        return targetUrl;
    }

    public void unsubscribeContact(String token) {
        contactRepository.findByUnsubscribeToken(token).ifPresent(contact -> {
            contact.setStatus("UNSUBSCRIBED");
            contactRepository.save(contact);
            globalSuppressionRepository.save(new GlobalSuppression(contact.getEmail(), "UNSUBSCRIBED"));
            System.out.println("🛑 UNSUBSCRIBED: " + contact.getEmail());
        });
    }

    public void processSnsNotification(String payload) {
        try {
            JsonNode rootNode = objectMapper.readTree(payload);
            if (!rootNode.has("Message")) return;
            JsonNode sesNode = objectMapper.readTree(rootNode.get("Message").asText());
            String type = sesNode.path("notificationType").asText();

            if ("Bounce".equalsIgnoreCase(type) || "Complaint".equalsIgnoreCase(type)) {
                String reason = "Bounce".equalsIgnoreCase(type) ? "BOUNCE" : "COMPLAINT";
                JsonNode recipients = sesNode.path(type.toLowerCase()).path(type.toLowerCase() + "dRecipients");
                for (JsonNode recipient : recipients) {
                    globalSuppressionRepository.save(new GlobalSuppression(recipient.path("emailAddress").asText(), reason));
                }
            }
        } catch (Exception e) {}
    }
}