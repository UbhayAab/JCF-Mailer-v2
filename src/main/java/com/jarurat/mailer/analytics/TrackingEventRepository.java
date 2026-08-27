package com.jarurat.mailer.analytics;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Every read here is filtered by classification. There is deliberately no method that
 * counts opens without saying which bucket, because that is the number that caused the
 * problem in the first place.
 *
 * campaignId is nullable on every query so the same statement serves the account-wide
 * view and the per-campaign view without a second copy of the SQL.
 */
public interface TrackingEventRepository extends JpaRepository<TrackingEvent, Long> {

    @Query("""
            select count(distinct e.email) from TrackingEvent e
            where e.eventType = :eventType
              and e.classification = :classification
              and (:campaignId is null or e.campaignId = :campaignId)
              and e.timestamp >= :from and e.timestamp < :to
            """)
    long countUnique(@Param("eventType") String eventType,
                     @Param("classification") OpenClassification classification,
                     @Param("campaignId") Long campaignId,
                     @Param("from") LocalDateTime from,
                     @Param("to") LocalDateTime to);

    @Query("""
            select count(e) from TrackingEvent e
            where e.eventType = :eventType
              and e.classification = :classification
              and (:campaignId is null or e.campaignId = :campaignId)
              and e.timestamp >= :from and e.timestamp < :to
            """)
    long countHits(@Param("eventType") String eventType,
                   @Param("classification") OpenClassification classification,
                   @Param("campaignId") Long campaignId,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);

    /**
     * Year, month, day and the unique count, one row per day that saw traffic. Grouping
     * by the extraction functions keeps this portable HQL instead of Postgres date_trunc.
     */
    @Query("""
            select year(e.timestamp), month(e.timestamp), day(e.timestamp), count(distinct e.email)
            from TrackingEvent e
            where e.eventType = :eventType
              and e.classification = :classification
              and (:campaignId is null or e.campaignId = :campaignId)
              and e.timestamp >= :from and e.timestamp < :to
            group by year(e.timestamp), month(e.timestamp), day(e.timestamp)
            order by year(e.timestamp), month(e.timestamp), day(e.timestamp)
            """)
    List<Object[]> dailyUnique(@Param("eventType") String eventType,
                               @Param("classification") OpenClassification classification,
                               @Param("campaignId") Long campaignId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    @Query("""
            select e.url as url, count(e) as clicks, count(distinct e.email) as uniqueClicks
            from TrackingEvent e
            where e.eventType = 'CLICK'
              and e.classification = :classification
              and (:campaignId is null or e.campaignId = :campaignId)
              and e.timestamp >= :from and e.timestamp < :to
              and e.url is not null
            group by e.url
            order by count(distinct e.email) desc
            """)
    List<LinkStat> topLinks(@Param("classification") OpenClassification classification,
                            @Param("campaignId") Long campaignId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to,
                            Pageable pageable);

    interface LinkStat {
        String getUrl();
        long getClicks();
        long getUniqueClicks();
    }

    /** Grouped raw so the null bucket is named in Java rather than in SQL. */
    @Query("""
            select e.client, count(distinct e.email)
            from TrackingEvent e
            where e.eventType = :eventType
              and e.classification = :classification
              and (:campaignId is null or e.campaignId = :campaignId)
              and e.timestamp >= :from and e.timestamp < :to
            group by e.client
            order by count(distinct e.email) desc
            """)
    List<Object[]> clientBreakdown(@Param("eventType") String eventType,
                                   @Param("classification") OpenClassification classification,
                                   @Param("campaignId") Long campaignId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Query("""
            select e.deviceType, count(distinct e.email)
            from TrackingEvent e
            where e.eventType = :eventType
              and e.classification = :classification
              and (:campaignId is null or e.campaignId = :campaignId)
              and e.timestamp >= :from and e.timestamp < :to
            group by e.deviceType
            order by count(distinct e.email) desc
            """)
    List<Object[]> deviceBreakdown(@Param("eventType") String eventType,
                                   @Param("classification") OpenClassification classification,
                                   @Param("campaignId") Long campaignId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /** Feeds the reclassification pass, which re-derives verdicts from the stored signals. */
    Page<TrackingEvent> findByEventType(String eventType, Pageable pageable);

    /**
     * One person, one send. Used by the journey engine to tell a machine-only open
     * apart from no open at all, which is the difference between filing someone as
     * "privacy protected, unknowable" and filing them as "did not open".
     */
    @Query("""
            select count(e) from TrackingEvent e
            where e.recipientId = :recipientId
              and e.eventType = :eventType
              and e.classification = :classification
            """)
    long countForRecipient(@Param("recipientId") Long recipientId,
                           @Param("eventType") String eventType,
                           @Param("classification") OpenClassification classification);

    /** Any hit at all for this send, whatever the classifier made of it. */
    @Query("""
            select count(e) from TrackingEvent e
            where e.recipientId = :recipientId and e.eventType = :eventType
            """)
    long countAnyForRecipient(@Param("recipientId") Long recipientId,
                              @Param("eventType") String eventType);

    /** Did this person follow one particular link? Feeds CLICKED_SPECIFIC. */
    @Query("""
            select count(e) from TrackingEvent e
            where e.recipientId = :recipientId
              and e.eventType = 'CLICK'
              and e.classification = com.jarurat.mailer.analytics.OpenClassification.HUMAN
              and e.url like concat('%', :urlFragment, '%')
            """)
    long countClicksOnLink(@Param("recipientId") Long recipientId,
                           @Param("urlFragment") String urlFragment);

    @Transactional
    @Modifying
    void deleteByCampaignId(Long campaignId);
}
