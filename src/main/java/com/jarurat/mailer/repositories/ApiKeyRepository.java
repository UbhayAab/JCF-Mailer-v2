package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    List<ApiKey> findAllByOrderByCreatedAtDesc();
}
