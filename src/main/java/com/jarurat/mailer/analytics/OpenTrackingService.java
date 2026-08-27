package com.jarurat.mailer.analytics;

import com.jarurat.mailer.models.CampaignRecipient;
import com.jarurat.mailer.repositories.CampaignRecipientRepository;
import com.jarurat.mailer.services.CampaignService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The single entry point for a tracking hit. TrackingController hands it the token plus
 * the User-Agent and remote address, and everything else happens here.
 *
 * This is where the open-inflation defect is actually fixed: the legacy counters on
 * CampaignRecipient and Subscriber are only moved when the classifier says a person was
 * behind the request. Apple's pre-fetch, a proxy's cache fill and a scanner's sweep all
 * still get written to tracking_event, they just stop pretending to be readers.
 */
@Service
public class OpenTrackingService {

    private final CampaignRecipientRepository recipients;
    private final TrackingEventRepository events;
    private final OpenClassifier classifier;
    private final CampaignService campaigns;

    /**
     * Only the reclassification pass touches this, to detach each page after writing it.
     * Field injected because Spring registers no EntityManager bean for the constructor
     * to take; @PersistenceContext is the supported route.
     */
    @PersistenceContext
    private EntityManager entityManager;

    public OpenTrackingService(CampaignRecipientRepository recipients,
                               TrackingEventRepository events,
                               OpenClassifier classifier,
                               CampaignService campaigns) {
        this.recipients = recipients;
        this.events = events;
        this.classifier = classifier;
        this.campaigns = campaigns;
    }

    /**
     * Records a pixel load. Silently does nothing for a token that matches no recipient:
     * the endpoint is public and unauthenticated, so writing a row per unknown token
     * would let anyone grow the table without limit.
     */
    public OpenClassifier.Verdict recordOpen(String token, String userAgent, String ip) {
        CampaignRecipient recipient = recipients.findByToken(token).orElse(null);
        if (recipient == null) return null;

        Long seconds = secondsSinceSent(recipient);
        OpenClassifier.Verdict verdict = classifier.classifyOpen(userAgent, ip, seconds);
        events.save(build(TrackingEvent.OPEN, verdict, recipient, null, userAgent, ip, seconds));

        if (verdict.classification().countsAsEngagement()) campaigns.trackOpen(token);
        return verdict;
    }

    /**
     * Records a link follow and returns the address to redirect to. The redirect always
     * happens, including for a hit classified as a bot: misreading a real reader as a
     * scanner is a wrong statistic, but breaking their link is a broken campaign.
     */
    public String recordClick(String token, String url, String userAgent, String ip) {
        CampaignRecipient recipient = recipients.findByToken(token).orElse(null);
        if (recipient == null) return url;

        Long seconds = secondsSinceSent(recipient);
        OpenClassifier.Verdict verdict = classifier.classifyClick(userAgent, ip, seconds);
        events.save(build(TrackingEvent.CLICK, verdict, recipient, url, userAgent, ip, seconds));

        // CampaignService.trackClick sets openedAt as a side effect, on the reasoning that
        // a click implies an open. That reasoning does not hold for a security gateway, so
        // a non-human click must not reach it or the pixel fix would leak straight back in.
        if (verdict.classification().countsAsEngagement()) return campaigns.trackClick(token, url);
        return url;
    }

    /**
     * A clock skew between the sending box and this one can produce a negative value.
     * Returning it unchanged is deliberate: the classifier rejects negatives and falls
     * back to the User-Agent rather than silently inventing a timing signal.
     */
    private static Long secondsSinceSent(CampaignRecipient recipient) {
        if (recipient.getSentAt() == null) return null;
        return Duration.between(recipient.getSentAt(), LocalDateTime.now()).toSeconds();
    }

    private static TrackingEvent build(String eventType, OpenClassifier.Verdict verdict,
                                       CampaignRecipient recipient, String url,
                                       String userAgent, String ip, Long seconds) {
        return new TrackingEvent(eventType, verdict.classification(),
                recipient.getCampaignId(), recipient.getSubscriberId(), recipient.getId(),
                recipient.getEmail(), url, userAgent, OpenClassifier.firstAddress(ip),
                verdict.client(), verdict.deviceType(), seconds, verdict.reason());
    }

    // ------------------------------------------------------------------
    // Recomputation
    // ------------------------------------------------------------------

    /**
     * Re-runs the current classifier over stored history and rewrites any verdict that
     * changed. This is the whole reason userAgent, ip and secondsSinceSent are kept on
     * the row rather than thrown away after the decision.
     *
     * It rewrites tracking_event only. The legacy openedAt and openCount fields on
     * CampaignRecipient are not retro-corrected, so anything counted before this package
     * shipped stays inflated. The analytics endpoints read tracking_event, not those
     * fields, so the reported numbers are correct from the cutover onwards.
     */
    @Transactional
    public int reclassify(int maxRows) {
        int limit = Math.max(1, Math.min(500_000, maxRows));
        int scanned = 0;
        int changed = 0;
        int page = 0;

        while (scanned < limit) {
            Page<TrackingEvent> chunk = events.findAll(PageRequest.of(page, 500, Sort.by("id")));
            if (chunk.isEmpty()) break;

            List<TrackingEvent> dirty = new ArrayList<>();
            for (TrackingEvent event : chunk.getContent()) {
                scanned++;
                OpenClassifier.Verdict verdict = TrackingEvent.CLICK.equals(event.getEventType())
                        ? classifier.classifyClick(event.getUserAgent(), event.getIp(), event.getSecondsSinceSent())
                        : classifier.classifyOpen(event.getUserAgent(), event.getIp(), event.getSecondsSinceSent());

                if (verdict.classification() != event.getClassification()) {
                    event.setClassification(verdict.classification());
                    event.setClient(verdict.client());
                    event.setDeviceType(verdict.deviceType());
                    event.setReason(verdict.reason());
                    dirty.add(event);
                    changed++;
                }
            }
            if (!dirty.isEmpty()) events.saveAll(dirty);

            // Without this the whole scan accumulates in one persistence context. At the
            // 500k ceiling that is several hundred megabytes of managed entities, which the
            // 2GB box this runs on does not have to spare.
            entityManager.flush();
            entityManager.clear();

            if (!chunk.hasNext()) break;
            page++;
        }
        return changed;
    }
}
