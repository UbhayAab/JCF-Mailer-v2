package com.jarurat.mailer.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface JourneySendRepository extends JpaRepository<JourneySend, Long> {
    Optional<JourneySend> findByNodeIdAndIterationNo(Long nodeId, int iterationNo);
    List<JourneySend> findByJourneyId(Long journeyId);

    @Transactional
    @Modifying
    void deleteByJourneyId(Long journeyId);
}
