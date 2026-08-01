package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

import java.util.List;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {
    // Spring magically writes the SQL for this just based on the method name!
    List<Contact> findByStatus(String status);
    // Finds a specific contact by their email address
    Optional<Contact> findByEmail(String email);
    Optional<Contact> findByUnsubscribeToken(String unsubscribeToken);
}