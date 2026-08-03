package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {
    List<Contact> findByCampaignNameAndStatus(String campaignName, String status);
    List<Contact> findByEmail(String email);
    Optional<Contact> findByUnsubscribeToken(String unsubscribeToken);
    boolean existsByCampaignNameAndEmail(String campaignName, String email);
    
    // EXCLUDES Unsubscribed contacts from both CSV lists!
    List<Contact> findByCampaignNameAndClickedUrlIsNotNullAndStatusNot(String campaignName, String status);
    List<Contact> findByCampaignNameAndClickedUrlIsNullAndStatusNot(String campaignName, String status);
    
    @Transactional
    @Modifying
    void deleteByCampaignName(String campaignName);
    
    long countByCampaignNameAndStatus(String campaignName, String status);
}