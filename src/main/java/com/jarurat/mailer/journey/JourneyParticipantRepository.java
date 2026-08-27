package com.jarurat.mailer.journey;

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

public interface JourneyParticipantRepository extends JpaRepository<JourneyParticipant, Long> {

    Optional<JourneyParticipant> findByJourneyIdAndSubscriberId(Long journeyId, Long subscriberId);

    long countByJourneyId(Long journeyId);
    long countByJourneyIdAndState(Long journeyId, String state);
    long countByJourneyIdAndBucket(Long journeyId, String bucket);

    Page<JourneyParticipant> findByJourneyIdAndBucketOrderByEnteredAtAsc(
            Long journeyId, String bucket, Pageable pageable);

    Page<JourneyParticipant> findByJourneyIdOrderByEnteredAtAsc(Long journeyId, Pageable pageable);

    List<JourneyParticipant> findByJourneyIdAndBucket(Long journeyId, String bucket);

    /**
     * The work queue. Ordered by due time so the person who has been waiting longest
     * is served first and a backlog cannot starve anyone indefinitely.
     */
    @Query("""
            select p from JourneyParticipant p
            where p.journeyId = :journeyId
              and p.state = 'ACTIVE'
              and p.nextRunAt is not null
              and p.nextRunAt <= :now
            order by p.nextRunAt asc
            """)
    List<JourneyParticipant> findDue(@Param("journeyId") Long journeyId,
                                     @Param("now") LocalDateTime now,
                                     Pageable pageable);

    /** Anyone still moving. This is how a journey decides it has finished. */
    @Query("""
            select count(p) from JourneyParticipant p
            where p.journeyId = :journeyId and p.state = 'ACTIVE'
            """)
    long countActive(@Param("journeyId") Long journeyId);

    /**
     * Every live participation for one address across every journey. Two journeys
     * mailing the same doctor in the same week is the second thing that will happen
     * once this feature ships, so the frequency guard needs this view.
     */
    @Query("""
            select p from JourneyParticipant p
            where p.email = :email and p.state = 'ACTIVE'
            """)
    List<JourneyParticipant> findActiveByEmail(@Param("email") String email);

    /** The conditional sheets, as counts. */
    @Query("""
            select p.bucket, count(p) from JourneyParticipant p
            where p.journeyId = :journeyId
            group by p.bucket
            """)
    List<Object[]> bucketCounts(@Param("journeyId") Long journeyId);

    /** How many people are standing on each node right now, for the canvas badges. */
    @Query("""
            select p.currentNodeId, count(p) from JourneyParticipant p
            where p.journeyId = :journeyId and p.state = 'ACTIVE' and p.currentNodeId is not null
            group by p.currentNodeId
            """)
    List<Object[]> nodeOccupancy(@Param("journeyId") Long journeyId);

    /** Arm, sheet, count. The raw material of the per-variant comparison. */
    @Query("""
            select p.variantArm, p.bucket, count(p) from JourneyParticipant p
            where p.journeyId = :journeyId and p.variantNodeId = :splitNodeId
            group by p.variantArm, p.bucket
            """)
    List<Object[]> variantBreakdown(@Param("journeyId") Long journeyId,
                                    @Param("splitNodeId") Long splitNodeId);

    /**
     * Who on a base sheet still needs admitting, already filtered.
     *
     * One statement rather than a member loop with a lookup per person: on a ten
     * thousand row list the loop is ten thousand queries a minute, which is the
     * difference between a tick that finishes and a tick that holds a connection
     * for the whole minute. The filters mirror queueFromList exactly, so a journey
     * admits precisely the people a blast to the same list would have mailed.
     */
    @Query(value = """
            select s.id,
                   s.email,
                   nullif(trim(coalesce(s.first_name, '') || ' ' || coalesce(s.last_name, '')), '')
            from subscriber s
            join list_member m on m.subscriber_id = s.id
            where m.list_id = :listId
              and s.status = 'SUBSCRIBED'
              and not exists (select 1 from global_suppression g where g.email = s.email)
              and not exists (select 1 from verification_result v
                              where v.email = s.email and v.verdict = 'UNDELIVERABLE')
              and not exists (select 1 from journey_participant p
                              where p.journey_id = :journeyId and p.subscriber_id = s.id)
            order by s.id
            limit :max
            """, nativeQuery = true)
    List<Object[]> findAdmissible(@Param("journeyId") Long journeyId,
                                  @Param("listId") Long listId,
                                  @Param("max") int max);

    @Transactional
    @Modifying
    void deleteByJourneyId(Long journeyId);
}
