package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.Subscriber;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface SubscriberRepository extends JpaRepository<Subscriber, Long> {

    Optional<Subscriber> findByEmail(String email);

    Optional<Subscriber> findByUnsubscribeToken(String token);

    boolean existsByEmail(String email);

    long countByStatus(String status);

    @Query("""
            select s from Subscriber s
            where (:q = '' or lower(s.email) like lower(concat('%', :q, '%'))
                           or lower(coalesce(s.firstName, '')) like lower(concat('%', :q, '%'))
                           or lower(coalesce(s.lastName, ''))  like lower(concat('%', :q, '%'))
                           or lower(coalesce(s.company, ''))   like lower(concat('%', :q, '%')))
              and (:status = '' or s.status = :status)
              and (:listId is null or s.id in (select m.subscriberId from ListMember m where m.listId = :listId))
            """)
    Page<Subscriber> search(@Param("q") String q,
                            @Param("status") String status,
                            @Param("listId") Long listId,
                            Pageable pageable);

    @Transactional
    @Modifying
    @Query("update Subscriber s set s.status = :status, s.updatedAt = CURRENT_TIMESTAMP where s.email = :email")
    int markStatusByEmail(@Param("email") String email, @Param("status") String status);
}
