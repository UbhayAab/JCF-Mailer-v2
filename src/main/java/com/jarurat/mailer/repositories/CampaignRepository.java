package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    Optional<Campaign> findByName(String name);

    boolean existsByName(String name);

    List<Campaign> findAllByOrderByCreatedAtDesc();

    List<Campaign> findByStatus(String status);

    /** Picked up by the scheduler once the clock passes scheduledAt. */
    List<Campaign> findByStatusAndScheduledAtLessThanEqual(String status, LocalDateTime cutoff);

    long countByStatus(String status);
}
