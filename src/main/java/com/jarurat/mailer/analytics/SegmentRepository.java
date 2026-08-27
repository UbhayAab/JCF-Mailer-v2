package com.jarurat.mailer.analytics;

import com.jarurat.mailer.models.CampaignRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The engagement segments, as one parameterised query rather than nine.
 *
 * A single statement with a :segment switch keeps the counting query and the listing
 * query provably in agreement. Two hand-written copies would drift the first time
 * someone adjusted one of them, and a tile whose number does not match the list
 * behind it destroys confidence in the whole screen.
 *
 * Extends the bare Repository marker on purpose: reporting has no business exposing
 * a second save or delete surface over campaign_recipient.
 */
public interface SegmentRepository extends Repository<CampaignRecipient, Long> {

    /*
     * The predicate, once, in words:
     *
     *   CLICKED             a verified human click
     *   OPENED_NOT_CLICKED  a verified human open and no click
     *   PRIVACY_UNKNOWN     no human open, but something machine-shaped fetched the pixel
     *   NOT_OPENED          sent, and nothing came back at all
     *   BOUNCED/UNSUB/...   the address is on the suppression list for that reason
     *   FAILED / SKIPPED    the send never happened
     *
     * openedAt and lastClickedAt are written only by the classifier's HUMAN path, so
     * "verified" is already baked into those two columns and does not need restating.
     */
    String PREDICATE = """
            r.campaignId = :campaignId
              and (
                (:segment = 'CLICKED'
                   and r.lastClickedAt is not null)
             or (:segment = 'OPENED_NOT_CLICKED'
                   and r.openedAt is not null and r.lastClickedAt is null)
             or (:segment = 'PRIVACY_UNKNOWN'
                   and r.status = 'SENT' and r.openedAt is null and r.lastClickedAt is null
                   and exists (select 1 from TrackingEvent e
                               where e.recipientId = r.id and e.eventType = 'OPEN'
                                 and e.classification <> com.jarurat.mailer.analytics.OpenClassification.HUMAN))
             or (:segment = 'NOT_OPENED'
                   and r.status = 'SENT' and r.openedAt is null and r.lastClickedAt is null
                   and not exists (select 1 from TrackingEvent e where e.recipientId = r.id))
             or (:segment = 'FAILED' and r.status = 'FAILED')
             or (:segment = 'SKIPPED' and r.status = 'SKIPPED')
             or (:segment in ('BOUNCED', 'UNSUBSCRIBED', 'COMPLAINED')
                   and exists (select 1 from com.jarurat.mailer.models.GlobalSuppression g
                               where g.email = r.email
                                 and ((:segment = 'BOUNCED' and g.reason = 'BOUNCE')
                                   or (:segment = 'COMPLAINED' and g.reason = 'COMPLAINT')
                                   or (:segment = 'UNSUBSCRIBED' and g.reason not in ('BOUNCE', 'COMPLAINT')))))
              )
            """;

    @Query("select count(r) from CampaignRecipient r where " + PREDICATE)
    long countIn(@Param("campaignId") Long campaignId, @Param("segment") String segment);

    @Query("""
            select r from CampaignRecipient r where
            """ + PREDICATE + """
              and (:q = '' or lower(r.email) like lower(concat('%', :q, '%'))
                           or lower(coalesce(r.name, '')) like lower(concat('%', :q, '%')))
            order by r.sentAt desc, r.id desc
            """)
    Page<CampaignRecipient> listIn(@Param("campaignId") Long campaignId,
                                   @Param("segment") String segment,
                                   @Param("q") String q,
                                   Pageable pageable);

    /** Streamed for the CSV export and for building a list, where paging would be noise. */
    @Query("select r from CampaignRecipient r where " + PREDICATE + " order by r.id")
    List<CampaignRecipient> allIn(@Param("campaignId") Long campaignId,
                                  @Param("segment") String segment);

    /** Time from send to first verified open, in seconds, for the distribution chart. */
    @Query("""
            select r.sentAt, r.openedAt from CampaignRecipient r
            where r.campaignId = :campaignId
              and r.sentAt is not null and r.openedAt is not null
            """)
    List<Object[]> openTimings(@Param("campaignId") Long campaignId);
}
