package com.jarurat.mailer.analytics;

import com.jarurat.mailer.models.CampaignRecipient;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Window-scoped aggregates over the send log. These live here rather than on
 * CampaignRecipientRepository because they are reporting queries with a date range,
 * and none of the sending paths need them.
 *
 * Extends the bare Repository marker on purpose: analytics has no business exposing a
 * second save/delete surface over campaign_recipient.
 */
public interface AnalyticsAggregateRepository extends Repository<CampaignRecipient, Long> {

    @Query("""
            select count(r) from CampaignRecipient r
            where r.status = 'SENT'
              and (:campaignId is null or r.campaignId = :campaignId)
              and r.sentAt >= :from and r.sentAt < :to
            """)
    long countSent(@Param("campaignId") Long campaignId,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);

    /**
     * Not windowed, and it cannot be: a FAILED or SKIPPED recipient never got a sentAt,
     * so there is no timestamp to filter on. Callers must present this as all time.
     */
    @Query("""
            select count(r) from CampaignRecipient r
            where r.status = :status
              and (:campaignId is null or r.campaignId = :campaignId)
            """)
    long countRecipientsByStatus(@Param("campaignId") Long campaignId, @Param("status") String status);

    @Query("""
            select year(r.sentAt), month(r.sentAt), day(r.sentAt), count(r)
            from CampaignRecipient r
            where r.status = 'SENT'
              and (:campaignId is null or r.campaignId = :campaignId)
              and r.sentAt >= :from and r.sentAt < :to
            group by year(r.sentAt), month(r.sentAt), day(r.sentAt)
            order by year(r.sentAt), month(r.sentAt), day(r.sentAt)
            """)
    List<Object[]> dailySent(@Param("campaignId") Long campaignId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);

    @Query("""
            select r.campaignId, count(r) from CampaignRecipient r
            where r.status = 'SENT'
              and r.sentAt >= :from and r.sentAt < :to
            group by r.campaignId
            order by count(r) desc
            """)
    List<Object[]> sentByCampaign(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Attributes a suppression to a campaign by matching the address and requiring the
     * suppression to be no older than the send. SES delivers bounce and complaint
     * notifications at the account level, not per message, so this is the closest
     * honest attribution available. It over-counts when the same address was mailed by
     * two campaigns before it bounced, which is why the API labels it "attributed".
     */
    @Query("""
            select count(distinct r.email) from CampaignRecipient r, com.jarurat.mailer.models.GlobalSuppression g
            where g.email = r.email
              and g.reason = :reason
              and r.status = 'SENT'
              and (:campaignId is null or r.campaignId = :campaignId)
              and r.sentAt >= :from and r.sentAt < :to
              and g.timestamp >= r.sentAt
            """)
    long countAttributedSuppressions(@Param("campaignId") Long campaignId,
                                     @Param("reason") String reason,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Query("""
            select count(g) from com.jarurat.mailer.models.GlobalSuppression g
            where g.reason = :reason and g.timestamp >= :from and g.timestamp < :to
            """)
    long countSuppressions(@Param("reason") String reason,
                           @Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to);
}
