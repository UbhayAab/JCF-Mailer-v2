package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.ListMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ListMemberRepository extends JpaRepository<ListMember, Long> {

    long countByListId(Long listId);

    boolean existsByListIdAndSubscriberId(Long listId, Long subscriberId);

    @Transactional
    @Modifying
    void deleteByListIdAndSubscriberId(Long listId, Long subscriberId);

    @Transactional
    @Modifying
    void deleteByListId(Long listId);

    @Transactional
    @Modifying
    void deleteBySubscriberId(Long subscriberId);

    /** Mailable size of a list: subscribed, not globally suppressed. */
    @Query(value = """
            select count(*) from list_member m
            join subscriber s on s.id = m.subscriber_id
            where m.list_id = :listId
              and s.status = 'SUBSCRIBED'
              and not exists (select 1 from global_suppression g where g.email = s.email)
            """, nativeQuery = true)
    long countMailable(@Param("listId") Long listId);
}
