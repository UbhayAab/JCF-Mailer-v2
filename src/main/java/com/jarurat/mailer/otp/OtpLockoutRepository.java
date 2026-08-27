package com.jarurat.mailer.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface OtpLockoutRepository extends JpaRepository<OtpLockout, String> {

    @Transactional
    @Modifying
    @Query("delete from OtpLockout l where l.lockedUntil < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
