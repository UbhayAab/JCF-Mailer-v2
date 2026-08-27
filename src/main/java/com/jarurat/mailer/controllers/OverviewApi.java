package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.AuditLog;
import com.jarurat.mailer.repositories.*;
import com.jarurat.mailer.services.CampaignService;
import com.jarurat.mailer.services.SesSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/overview")
public class OverviewApi {

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM, HH:mm");

    private final SubscriberRepository subscribers;
    private final MailingListRepository lists;
    private final CampaignRepository campaigns;
    private final CampaignRecipientRepository recipients;
    private final GlobalSuppressionRepository suppression;
    private final TransactionalLogRepository transactional;
    private final AuditLogRepository audit;
    private final SesSender ses;
    private final String sendingDomain;

    public OverviewApi(SubscriberRepository subscribers, MailingListRepository lists,
                       CampaignRepository campaigns, CampaignRecipientRepository recipients,
                       GlobalSuppressionRepository suppression, TransactionalLogRepository transactional,
                       AuditLogRepository audit, SesSender ses,
                       @Value("${aws.ses.domain:jarurat.care}") String sendingDomain) {
        this.subscribers = subscribers;
        this.lists = lists;
        this.campaigns = campaigns;
        this.recipients = recipients;
        this.suppression = suppression;
        this.transactional = transactional;
        this.audit = audit;
        this.ses = ses;
        this.sendingDomain = sendingDomain;
    }

    @GetMapping
    public Map<String, Object> overview(Authentication auth) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Every tile is gated on the same permission that guards the page behind
        // it, so an HR account never learns how large the marketing base is.
        boolean marketing = has(auth, "CAMPAIGNS_READ");
        boolean audience = has(auth, "SUBSCRIBERS_READ");

        if (audience) {
            out.put("subscribers", subscribers.count());
            out.put("subscribedCount", subscribers.countByStatus("SUBSCRIBED"));
        }
        if (has(auth, "LISTS_READ")) out.put("lists", lists.count());

        if (marketing) {
            long sent = recipients.countByStatus("SENT");
            long opened = recipients.countByOpenedAtIsNotNull();
            long clicked = recipients.countByLastClickedAtIsNotNull();
            out.put("campaigns", campaigns.count());
            out.put("campaignsSending", campaigns.countByStatus("SENDING"));
            out.put("campaignsScheduled", campaigns.countByStatus("SCHEDULED"));
            out.put("totalSent", sent);
            out.put("totalOpened", opened);
            out.put("totalClicked", clicked);
            out.put("openRate", CampaignService.rate(opened, sent));
            out.put("clickRate", CampaignService.rate(clicked, sent));
        }
        if (has(auth, "SUPPRESSION_READ")) {
            out.put("unsubscribed", suppression.countByReason("UNSUBSCRIBED"));
            out.put("bounced", suppression.countByReason("BOUNCE"));
            out.put("complaints", suppression.countByReason("COMPLAINT"));
        }
        if (has(auth, "TRANSACTIONAL_READ")) {
            out.put("transactional24h", transactional.countByTimestampAfter(LocalDateTime.now().minusDays(1)));
            out.put("transactionalTotal", transactional.count());
        }

        out.put("ses", ses.accountHealth());
        out.put("identity", ses.identityHealth(sendingDomain));

        List<Map<String, Object>> activity = new ArrayList<>();
        if (has(auth, "AUDIT_READ")) {
            for (AuditLog log : audit.findTop15ByOrderByTimestampDesc()) {
                activity.add(Map.of(
                        "actor", nz(log.getActor()),
                        "action", nz(log.getAction()),
                        "target", nz(log.getTarget()),
                        "detail", nz(log.getDetail()),
                        "at", log.getTimestamp() == null ? "" : log.getTimestamp().format(STAMP)));
            }
        }
        out.put("activity", activity);
        return out;
    }

    private boolean has(Authentication auth, String permission) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> permission.equals(a.getAuthority()));
    }

    static String nz(String s) { return s == null ? "" : s; }
}
