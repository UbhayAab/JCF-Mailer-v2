package com.jarurat.mailer.repositories;
import com.jarurat.mailer.models.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CampaignRepository extends JpaRepository<Campaign, String> {}