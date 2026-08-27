package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.CampaignRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    Optional<CampaignRecipient> findByToken(String token);

    List<CampaignRecipient> findByCampaignIdAndStatus(Long campaignId, String status, Pageable pageable);

    long countByCampaignId(Long campaignId);
    long countByCampaignIdAndStatus(Long campaignId, String status);
    long countByCampaignIdAndOpenedAtIsNotNull(Long campaignId);
    long countByCampaignIdAndLastClickedAtIsNotNull(Long campaignId);

    long countByStatus(String status);
    long countByOpenedAtIsNotNull();
    long countByLastClickedAtIsNotNull();

    @Transactional
    @Modifying
    void deleteByCampaignId(Long campaignId);

    @Query("""
            select r from CampaignRecipient r
            where r.campaignId = :campaignId
              and (:status = '' or r.status = :status)
              and (:q = '' or lower(r.email) like lower(concat('%', :q, '%'))
                           or lower(coalesce(r.name, '')) like lower(concat('%', :q, '%')))
            """)
    Page<CampaignRecipient> search(@Param("campaignId") Long campaignId,
                                   @Param("status") String status,
                                   @Param("q") String q,
                                   Pageable pageable);

    /**
     * Snapshots the audience in one statement. A per-row JPA loop would take
     * minutes on a 50k list; this takes milliseconds and skips anyone
     * unsubscribed or globally suppressed at queue time.
     */
    @Transactional
    @Modifying
    @Query(value = """
            insert into campaign_recipient
                (campaign_id, subscriber_id, email, name, status, token, open_count, click_count)
            select :campaignId, s.id, s.email,
                   nullif(trim(coalesce(s.first_name, '') || ' ' || coalesce(s.last_name, '')), ''),
                   'PENDING', gen_random_uuid()::text, 0, 0
            from subscriber s
            join list_member m on m.subscriber_id = s.id
            where m.list_id = :listId
              and s.status = 'SUBSCRIBED'
              and not exists (select 1 from global_suppression g where g.email = s.email)
              -- An address verification already proved undeliverable must never be
              -- queued. Sending to it produces a hard bounce, and enough of those
              -- cost the sending domain its reputation, which is the one asset here
              -- that cannot be bought back.
              and not exists (select 1 from verification_result v
                              where v.email = s.email and v.verdict = 'UNDELIVERABLE')
            on conflict (campaign_id, subscriber_id) do nothing
            """, nativeQuery = true)
    int queueFromList(@Param("campaignId") Long campaignId, @Param("listId") Long listId);

    @Transactional
    @Modifying
    @Query("update CampaignRecipient r set r.status = 'PENDING', r.failReason = null " +
           "where r.campaignId = :campaignId and r.status = 'FAILED'")
    int requeueFailed(@Param("campaignId") Long campaignId);

    /** Stops a send reaching someone who unsubscribed after the snapshot was taken. */
    @Transactional
    @Modifying
    @Query("update CampaignRecipient r set r.status = 'SKIPPED' " +
           "where r.email = :email and r.status = 'PENDING'")
    int skipPendingForEmail(@Param("email") String email);
}
