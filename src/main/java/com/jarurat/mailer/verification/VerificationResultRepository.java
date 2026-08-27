package com.jarurat.mailer.verification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface VerificationResultRepository extends JpaRepository<VerificationResult, String> {

    /**
     * The resume query. Anything that comes back has a usable answer already and
     * is skipped, which is what lets a 12,000 address run be interrupted and
     * restarted without redoing the work.
     */
    @Query("select v.email from VerificationResult v where v.email in :emails and v.checkedAt > :since")
    List<String> findFreshEmails(@Param("emails") Collection<String> emails,
                                 @Param("since") LocalDateTime since);

    /** The gate the campaign dispatcher leans on. */
    @Query("""
            select v.email from VerificationResult v
            where v.email in :emails
              and v.verdict in :verdicts
              and v.checkedAt > :since
            """)
    List<String> findEmailsWithVerdict(@Param("emails") Collection<String> emails,
                                       @Param("verdicts") Collection<String> verdicts,
                                       @Param("since") LocalDateTime since);

    /**
     * Riskiest first, which the console labels "sorted by risk". Descending on
     * the verdict string gives exactly UNDELIVERABLE, RISKY, DELIVERABLE, so no
     * CASE expression is needed. A CASE in ORDER BY is not strictly legal JPQL
     * and Spring Data has to re-parse this query to derive the count query for
     * paging, so the plain sort is the safer one. Renaming a Verdict constant
     * would change this ordering.
     */
    @Query("""
            select v from VerificationResult v
            where (:q = '' or lower(v.email) like lower(concat('%', :q, '%')))
              and (:verdict = '' or v.verdict = :verdict)
            order by v.verdict desc, v.email asc
            """)
    Page<VerificationResult> search(@Param("q") String q,
                                    @Param("verdict") String verdict,
                                    Pageable pageable);

    /**
     * Same table, scoped to one list. Native because the join reaches into
     * list_member and subscriber, and the LEFT JOIN is what surfaces the
     * addresses on the list that nobody has checked yet.
     */
    @Query(value = """
            select coalesce(v.verdict, 'UNCHECKED') as verdict, count(*) as total
            from list_member m
            join subscriber s on s.id = m.subscriber_id
            left join verification_result v
                   on v.email = s.email and v.checked_at > :since
            where m.list_id = :listId
            group by coalesce(v.verdict, 'UNCHECKED')
            """, nativeQuery = true)
    List<VerdictCount> countByVerdictForList(@Param("listId") Long listId,
                                             @Param("since") LocalDateTime since);

    @Query(value = """
            select coalesce(v.verdict, 'UNCHECKED') as verdict, count(*) as total
            from subscriber s
            left join verification_result v
                   on v.email = s.email and v.checked_at > :since
            group by coalesce(v.verdict, 'UNCHECKED')
            """, nativeQuery = true)
    List<VerdictCount> countByVerdictForEveryone(@Param("since") LocalDateTime since);

    interface VerdictCount {
        String getVerdict();
        long getTotal();
    }

    /** Expired rows are dead weight: the read path already refuses to trust them. */
    @Transactional
    @Modifying
    @Query("delete from VerificationResult v where v.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
