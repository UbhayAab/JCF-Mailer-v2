package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.GlobalSuppression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlobalSuppressionRepository extends JpaRepository<GlobalSuppression, String> {

    long countByReason(String reason);

    @Query("""
            select g from GlobalSuppression g
            where (:q = '' or lower(g.email) like lower(concat('%', :q, '%')))
              and (:reason = '' or g.reason = :reason)
            order by g.timestamp desc
            """)
    Page<GlobalSuppression> search(@Param("q") String q,
                                   @Param("reason") String reason,
                                   Pageable pageable);
}
