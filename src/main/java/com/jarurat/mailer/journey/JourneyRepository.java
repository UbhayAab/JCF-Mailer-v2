package com.jarurat.mailer.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JourneyRepository extends JpaRepository<Journey, Long> {
    List<Journey> findAllByOrderByCreatedAtDesc();
    List<Journey> findByStatus(String status);
    boolean existsByName(String name);
    Optional<Journey> findByName(String name);
}
