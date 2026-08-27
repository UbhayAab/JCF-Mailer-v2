package com.jarurat.mailer.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface JourneyNodeRepository extends JpaRepository<JourneyNode, Long> {
    List<JourneyNode> findByJourneyIdOrderByStageAscSortOrderAsc(Long journeyId);
    List<JourneyNode> findByJourneyIdAndType(Long journeyId, String type);
    Optional<JourneyNode> findByJourneyIdAndNodeKey(Long journeyId, String nodeKey);
    long countByJourneyId(Long journeyId);

    @Transactional
    @Modifying
    void deleteByJourneyId(Long journeyId);
}
