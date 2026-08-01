package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {
    List<Contact> findByStatus(String status);
    List<Contact> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<Contact> findByUnsubscribeToken(String unsubscribeToken);
    
    // NEW: Used by the Dashboard to show live analytics
    long countByStatus(String status);
}