package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.User;
import com.jarurat.mailer.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findAllByOrderByCreatedAtAsc();
    long countByRole(Role role);
    long countByActiveTrue();
}
