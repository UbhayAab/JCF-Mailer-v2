package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop15ByOrderByTimestampDesc();

    @Query("""
            select a from AuditLog a
            where (:q = '' or lower(coalesce(a.actor, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(a.action, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(a.target, '')) like lower(concat('%', :q, '%')))
            order by a.timestamp desc
            """)
    Page<AuditLog> search(@Param("q") String q, Pageable pageable);
}
