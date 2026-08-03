package com.jarurat.mailer.repositories;
import com.jarurat.mailer.models.GlobalSuppression;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlobalSuppressionRepository extends JpaRepository<GlobalSuppression, String> {
    long countByReason(String reason);
}