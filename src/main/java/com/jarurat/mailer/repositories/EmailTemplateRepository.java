package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {
    Optional<EmailTemplate> findBySlug(String slug);
    boolean existsBySlug(String slug);
    List<EmailTemplate> findAllByOrderByCreatedAtDesc();
    List<EmailTemplate> findByTypeOrderByCreatedAtDesc(String type);
}
