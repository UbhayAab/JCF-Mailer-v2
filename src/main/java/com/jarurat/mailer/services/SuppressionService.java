package com.jarurat.mailer.services;

import com.jarurat.mailer.models.CampaignRecipient;
import com.jarurat.mailer.models.GlobalSuppression;
import com.jarurat.mailer.repositories.CampaignRecipientRepository;
import com.jarurat.mailer.repositories.GlobalSuppressionRepository;
import com.jarurat.mailer.repositories.SubscriberRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sesv2.model.*;

@Service
public class SuppressionService {

    private final GlobalSuppressionRepository suppressionRepository;
    private final SubscriberRepository subscriberRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final SesSender ses;

    public SuppressionService(GlobalSuppressionRepository suppressionRepository,
                              SubscriberRepository subscriberRepository,
                              CampaignRecipientRepository recipientRepository,
                              SesSender ses) {
        this.suppressionRepository = suppressionRepository;
        this.subscriberRepository = subscriberRepository;
        this.recipientRepository = recipientRepository;
        this.ses = ses;
    }

    public boolean isSuppressed(String email) {
        return suppressionRepository.existsById(email.trim().toLowerCase());
    }

    /**
     * One entry point for every kind of opt-out. Suppressing has to hit three
     * places at once, otherwise an in-flight campaign keeps mailing someone who
     * just unsubscribed.
     */
    @Transactional
    public void suppress(String email, String reason) {
        String clean = email.trim().toLowerCase();
        suppressionRepository.save(new GlobalSuppression(clean, reason));
        subscriberRepository.markStatusByEmail(clean,
                "BOUNCE".equals(reason) ? "BOUNCED"
                        : "COMPLAINT".equals(reason) ? "COMPLAINED" : "UNSUBSCRIBED");
        recipientRepository.skipPendingForEmail(clean);
    }

    @Transactional
    public void unsuppress(String email) {
        String clean = email.trim().toLowerCase();
        suppressionRepository.deleteById(clean);
        subscriberRepository.markStatusByEmail(clean, "SUBSCRIBED");
    }

    /** Called from the tracking link in a delivered email. */
    @Transactional
    public String unsubscribeByToken(String token) {
        CampaignRecipient recipient = recipientRepository.findByToken(token).orElse(null);
        if (recipient != null) {
            suppress(recipient.getEmail(), "UNSUBSCRIBED");
            return recipient.getEmail();
        }
        // Older mail carried the subscriber's own token
        return subscriberRepository.findByUnsubscribeToken(token)
                .map(s -> { suppress(s.getEmail(), "UNSUBSCRIBED"); return s.getEmail(); })
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Bounces and complaints
    // ------------------------------------------------------------------

    /*
     * processSnsNotification used to live here, fed by a public unauthenticated
     * endpoint that verified no signature and pinned no topic, which made
     * "suppress any address forever" an anonymous POST. It is gone along with that
     * endpoint. syncFromSes below is the only bounce and complaint source now, and
     * it is the authoritative one: it reads SES's own account level suppression
     * list rather than trusting a payload someone handed us.
     */

    /**
     * No SNS topic is attached to this AWS account and the instance role cannot
     * create one, so mirror SES's own account-level suppression list instead.
     * That list is authoritative for bounces and complaints either way.
     */
    @Scheduled(initialDelay = 45_000, fixedDelay = 900_000)
    public void syncFromSes() {
        try {
            int imported = 0;
            String nextToken = null;
            do {
                ListSuppressedDestinationsRequest.Builder req =
                        ListSuppressedDestinationsRequest.builder().pageSize(100);
                if (nextToken != null) req.nextToken(nextToken);
                ListSuppressedDestinationsResponse res = ses.client().listSuppressedDestinations(req.build());

                for (SuppressedDestinationSummary s : res.suppressedDestinationSummaries()) {
                    String email = s.emailAddress().toLowerCase();
                    if (!suppressionRepository.existsById(email)) {
                        suppress(email, s.reasonAsString());
                        imported++;
                    }
                }
                nextToken = res.nextToken();
            } while (nextToken != null);

            if (imported > 0) System.out.println("Imported " + imported + " suppressions from SES.");
        } catch (Exception e) {
            System.err.println("SES suppression sync failed: " + e.getMessage());
        }
    }
}
