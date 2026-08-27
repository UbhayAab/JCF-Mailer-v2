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

public interface JourneyEventRepository extends JpaRepository<JourneyEvent, Long> {

    Page<JourneyEvent> findByJourneyIdOrderByTimestampDesc(Long journeyId, Pageable pageable);

    List<JourneyEvent> findByParticipantIdOrderByTimestampAsc(Long participantId);

    /** Feeds the circuit breaker: how much mail this journey has produced lately. */
    @Query("""
            select count(e) from JourneyEvent e
            where e.journeyId = :journeyId and e.eventType = 'SENT' and e.timestamp >= :since
            """)
    long countSentSince(@Param("journeyId") Long journeyId, @Param("since") LocalDateTime since);

    @Transactional
    @Modifying
    void deleteByJourneyId(Long journeyId);
}
