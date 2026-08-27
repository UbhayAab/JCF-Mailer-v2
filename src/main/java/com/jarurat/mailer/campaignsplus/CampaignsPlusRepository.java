package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.models.CampaignRecipient;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read only queries the importer and the pre-send checks need. They live here
 * rather than on the existing repositories so nothing already in production has
 * to change to add a check.
 */
public interface CampaignsPlusRepository extends Repository<CampaignRecipient, Long> {

    /**
     * One round trip instead of one existence check per CSV row. A 40k row file
     * against a 20k suppression list would otherwise be 40k queries.
     */
    @Query(value = "select email from global_suppression", nativeQuery = true)
    List<String> allSuppressedEmails();

    /**
     * Belt and braces. suppress() writes both rows, but anything that marks a
     * subscriber bounced without a suppression row would otherwise slip back in
     * through an import.
     */
    @Query(value = "select email from subscriber where status <> 'SUBSCRIBED'", nativeQuery = true)
    List<String> allUnmailableEmails();

    /**
     * Anyone still queued who has landed on the suppression list since the
     * snapshot was taken. This is the one leak the send path cannot fix on its
     * own if the process dies mid run, so it blocks a send.
     */
    @Query(value = """
            select count(*) from campaign_recipient r
            join global_suppression g on g.email = r.email
            where r.campaign_id = :campaignId and r.status = 'PENDING'
            """, nativeQuery = true)
    long countSuppressedPending(@Param("campaignId") Long campaignId);

    @Query(value = """
            select r.email from campaign_recipient r
            join global_suppression g on g.email = r.email
            where r.campaign_id = :campaignId and r.status = 'PENDING'
            order by r.email
            limit :max
            """, nativeQuery = true)
    List<String> sampleSuppressedPending(@Param("campaignId") Long campaignId, @Param("max") int max);

    /**
     * The way out of a SUPPRESSED_QUEUED block. Re-queueing cannot clear those
     * rows because queueFromList is ON CONFLICT DO NOTHING and never revisits a
     * row that already exists, so without this the campaign is blocked for good.
     * Marking them SKIPPED is exactly what the send loop does when it meets one.
     */
    @Transactional
    @Modifying
    @Query(value = """
            update campaign_recipient r set status = 'SKIPPED'
            where r.campaign_id = :campaignId and r.status = 'PENDING'
              and exists (select 1 from global_suppression g where g.email = r.email)
            """, nativeQuery = true)
    int skipSuppressedPending(@Param("campaignId") Long campaignId);

    /** Members the queue step will drop: unsubscribed, bounced or suppressed. */
    @Query(value = """
            select count(*) from list_member m
            join subscriber s on s.id = m.subscriber_id
            where m.list_id = :listId
              and (s.status <> 'SUBSCRIBED'
                   or exists (select 1 from global_suppression g where g.email = s.email))
            """, nativeQuery = true)
    long countListUnmailable(@Param("listId") Long listId);

    /** Addresses with no send history at all, which is where bounces come from. */
    @Query(value = """
            select count(*) from list_member m
            join subscriber s on s.id = m.subscriber_id
            where m.list_id = :listId and coalesce(s.total_sent, 0) = 0
            """, nativeQuery = true)
    long countListNeverMailed(@Param("listId") Long listId);

    @Query(value = """
            select count(*) from campaign_recipient r
            where r.status = 'SENT' and r.sent_at >= :since
            """, nativeQuery = true)
    long countSentSince(@Param("since") LocalDateTime since);

    @Query(value = """
            select count(*) from global_suppression g
            where g.timestamp >= :since and upper(g.reason) like 'BOUNCE%'
            """, nativeQuery = true)
    long countBouncesSince(@Param("since") LocalDateTime since);

    @Query(value = """
            select count(*) from global_suppression g
            where g.timestamp >= :since and upper(g.reason) like 'COMPLAINT%'
            """, nativeQuery = true)
    long countComplaintsSince(@Param("since") LocalDateTime since);
}
