package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.TransactionalLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface TransactionalLogRepository extends JpaRepository<TransactionalLog, Long> {

    long countByTimestampAfter(LocalDateTime after);
    long countByStatus(String status);

    @Query("""
            select t from TransactionalLog t
            where (:q = '' or lower(t.toEmail) like lower(concat('%', :q, '%'))
                           or lower(coalesce(t.templateSlug, '')) like lower(concat('%', :q, '%')))
              and (:status = '' or t.status = :status)
            order by t.timestamp desc
            """)
    Page<TransactionalLog> search(@Param("q") String q,
                                  @Param("status") String status,
                                  Pageable pageable);
}
