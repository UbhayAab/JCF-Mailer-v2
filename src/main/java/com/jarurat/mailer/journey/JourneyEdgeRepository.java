package com.jarurat.mailer.journey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface JourneyEdgeRepository extends JpaRepository<JourneyEdge, Long> {
    List<JourneyEdge> findByJourneyIdOrderBySortOrderAsc(Long journeyId);
    List<JourneyEdge> findByFromNodeIdOrderBySortOrderAsc(Long fromNodeId);
    List<JourneyEdge> findByToNodeId(Long toNodeId);

    @Transactional
    @Modifying
    void deleteByJourneyId(Long journeyId);

    /** Removing a node has to take its arrows with it, in both directions. */
    @Transactional
    @Modifying
    @Query("delete from JourneyEdge e where e.fromNodeId = :nodeId or e.toNodeId = :nodeId")
    void deleteTouching(@Param("nodeId") Long nodeId);
}
