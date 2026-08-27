package com.jarurat.mailer.security;

import com.jarurat.mailer.models.AuditLog;
import com.jarurat.mailer.models.User;
import com.jarurat.mailer.repositories.AuditLogRepository;
import com.jarurat.mailer.repositories.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Locks an account for 15 minutes after 5 bad passwords, to blunt credential stuffing. */
@Component
public class LoginAttemptListener {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    public LoginAttemptListener(UserRepository userRepository, AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        String email = String.valueOf(event.getAuthentication().getName()).toLowerCase();
        userRepository.findByEmail(email).ifPresent(user -> {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_ATTEMPTS) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                user.setFailedLoginAttempts(0);
                auditLogRepository.save(new AuditLog(email, "ACCOUNT_LOCKED", email,
                        "Locked for " + LOCK_MINUTES + " minutes after " + MAX_ATTEMPTS + " failed attempts", null));
            }
            userRepository.save(user);
        });
        auditLogRepository.save(new AuditLog(email, "LOGIN_FAILED", email, null, null));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        Object principal = event.getAuthentication().getPrincipal();
        if (!(principal instanceof AppUserDetails details)) return;

        User user = details.getUser();
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        auditLogRepository.save(new AuditLog(user.getEmail(), "LOGIN_SUCCESS", user.getEmail(), null, null));
    }
}
