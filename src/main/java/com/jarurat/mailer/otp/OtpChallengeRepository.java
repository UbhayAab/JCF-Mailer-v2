package com.jarurat.mailer.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {

    Optional<OtpChallenge> findByPublicId(String publicId);

    /**
     * The newest live challenge for an address, for callers that keep no state and
     * verify by email rather than by challenge id.
     */
    @Query("""
            select c from OtpChallenge c
            where c.email = :email and c.purpose = :purpose
              and c.consumedAt is null and c.expiresAt > :now
              and c.attempts < c.maxAttempts
            order by c.createdAt desc
            """)
    List<OtpChallenge> findLive(@Param("email") String email,
                                @Param("purpose") String purpose,
                                @Param("now") LocalDateTime now);

    /*
     * The rate limits are counts over this table rather than a separate counter.
     * Every request writes a row whether or not a message went out, so the count is
     * exact, needs no extra state, and cannot be reset by restarting the process.
     */

    @Query("""
            select count(c) from OtpChallenge c
            where c.email = :email and c.purpose = :purpose and c.createdAt >= :since
            """)
    long countForEmailSince(@Param("email") String email,
                            @Param("purpose") String purpose,
                            @Param("since") LocalDateTime since);

    @Query("""
            select count(c) from OtpChallenge c
            where c.apiKeyName = :apiKeyName and c.createdAt >= :since
            """)
    long countForKeySince(@Param("apiKeyName") String apiKeyName,
                          @Param("since") LocalDateTime since);

    @Query("""
            select count(c) from OtpChallenge c
            where c.sendStatus = 'SENT' and c.lastSentAt >= :since
            """)
    long countSentSince(@Param("since") LocalDateTime since);

    Optional<OtpChallenge> findByVerificationTokenHash(String verificationTokenHash);

    @Transactional
    @Modifying
    @Query("delete from OtpChallenge c where c.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
