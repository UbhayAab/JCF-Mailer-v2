package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {
    List<Contact> findByStatus(String status);
    
    // NEW: Returns a list so it won't crash if duplicate emails exist
    List<Contact> findByEmail(String email);
    
    // NEW: Super fast check to see if an email exists for the CSV uploader
    boolean existsByEmail(String email);
    
    // Tokens are UUIDs, so they are guaranteed to be unique
    Optional<Contact> findByUnsubscribeToken(String unsubscribeToken);
}