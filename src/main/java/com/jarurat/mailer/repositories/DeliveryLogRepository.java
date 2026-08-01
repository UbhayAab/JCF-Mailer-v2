package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.DeliveryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {
    
    // NEW: Fetches the 10 most recent logs for the UI table
    List<DeliveryLog> findTop10ByOrderByTimestampDesc();
}