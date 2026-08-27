package com.jarurat.mailer.messagelog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MessageLogRepository extends JpaRepository<MessageLogEntry, Long> {

    /**
     * SES fans one send out to several delivery events, and a campaign can mail
     * the same person twice, so this is deliberately newest-first rather than a
     * unique lookup.
     */
    Optional<MessageLogEntry> findFirstBySesMessageIdOrderByTimestampDesc(String sesMessageId);

    List<MessageLogEntry> findByMessageId(String messageId);

    /**
     * The send a delivery report belongs to. The mail server's log line has no
     * Message-ID, so sender plus recipient plus a time window is the join, and
     * only rows still waiting for a reply are eligible: a row that already carries
     * one must not be rewritten by a later attempt for the same pair.
     */
    @Query("""
            select m from MessageLogEntry m
            where m.direction = 'OUTBOUND'
              and lower(coalesce(m.fromEmail, '')) = :fromAddr
              and lower(coalesce(m.toEmail, '')) = :toAddr
              and m.outcome in ('SENT', 'QUEUED')
              and m.reportingMta is null
              and m.timestamp >= :since
              and m.timestamp <= :until
            order by m.timestamp desc
            """)
    List<MessageLogEntry> findOpenOutbound(@Param("fromAddr") String fromAddr,
                                           @Param("toAddr") String toAddr,
                                           @Param("since") LocalDateTime since,
                                           @Param("until") LocalDateTime until,
                                           Pageable pageable);

    long countByTimestampAfter(LocalDateTime after);
    long countByOutcomeAndTimestampAfter(String outcome, LocalDateTime after);
    long countByDirectionAndTimestampAfter(String direction, LocalDateTime after);

    /**
     * Every filter is optional and switched off by a sentinel rather than a null,
     * because an untyped null parameter reaching the Postgres driver is the one
     * failure mode this query cannot recover from. '' means "any" for the string
     * filters and -1 means "any" for the campaign. The date range is always
     * bound; the service supplies wide defaults when the caller gives none.
     */
    @Query("""
            select m from MessageLogEntry m
            where (:q = '' or lower(coalesce(m.toEmail, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(m.fromEmail, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(m.subject, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(m.messageId, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(m.sesMessageId, '')) like lower(concat('%', :q, '%')))
              and (:toAddr = '' or lower(coalesce(m.toEmail, '')) like lower(concat('%', :toAddr, '%')))
              and (:fromAddr = '' or lower(coalesce(m.fromEmail, '')) like lower(concat('%', :fromAddr, '%')))
              and (:subject = '' or lower(coalesce(m.subject, '')) like lower(concat('%', :subject, '%')))
              and (:messageId = '' or m.messageId = :messageId or m.sesMessageId = :messageId)
              and (:direction = '' or m.direction = :direction)
              and (:outcome = '' or m.outcome = :outcome)
              and (:campaignId = -1L or m.campaignId = :campaignId)
              and m.timestamp >= :since
              and m.timestamp <= :until
            order by m.timestamp desc
            """)
    Page<MessageLogEntry> search(@Param("q") String q,
                                 @Param("toAddr") String toAddr,
                                 @Param("fromAddr") String fromAddr,
                                 @Param("subject") String subject,
                                 @Param("messageId") String messageId,
                                 @Param("direction") String direction,
                                 @Param("outcome") String outcome,
                                 @Param("campaignId") Long campaignId,
                                 @Param("since") LocalDateTime since,
                                 @Param("until") LocalDateTime until,
                                 Pageable pageable);

    /** Outcome tallies for one search window, so the console header never re-counts page by page. */
    @Query("""
            select m.outcome, count(m) from MessageLogEntry m
            where m.timestamp >= :since and m.timestamp <= :until
            group by m.outcome
            """)
    List<Object[]> tallyByOutcome(@Param("since") LocalDateTime since,
                                  @Param("until") LocalDateTime until);

    @Transactional
    @Modifying
    @Query("delete from MessageLogEntry m where m.timestamp < :cutoff")
    int purgeOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
