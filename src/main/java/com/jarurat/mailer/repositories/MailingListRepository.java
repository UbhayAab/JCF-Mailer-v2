package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.MailingList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MailingListRepository extends JpaRepository<MailingList, Long> {
    Optional<MailingList> findByName(String name);
    boolean existsByName(String name);
    List<MailingList> findAllByOrderByCreatedAtDesc();
}
