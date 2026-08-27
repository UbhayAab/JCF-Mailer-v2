package com.jarurat.mailer.security;

import com.jarurat.mailer.models.ApiKey;
import com.jarurat.mailer.repositories.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates machine callers on /api/v1/**. Keys arrive as
 * "Authorization: Bearer jcf_live_..." or "X-API-Key: jcf_live_...".
 * Only the SHA-256 hash is ever compared, so a database leak does not hand
 * anyone a working credential.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = extractKey(request);
        if (presented != null) {
            Optional<ApiKey> match = apiKeyRepository.findByKeyHash(ApiKeyHasher.sha256(presented));
            if (match.isPresent() && !match.get().isRevoked()) {
                ApiKey key = match.get();
                var auth = new UsernamePasswordAuthenticationToken(
                        "apikey:" + key.getName(), null,
                        List.of(new SimpleGrantedAuthority(Permission.TRANSACTIONAL_SEND.name()),
                                new SimpleGrantedAuthority(Permission.TRANSACTIONAL_READ.name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
                request.setAttribute("apiKeyName", key.getName());

                key.setLastUsedAt(LocalDateTime.now());
                key.setUseCount(key.getUseCount() + 1);
                apiKeyRepository.save(key);
            }
        }
        chain.doFilter(request, response);
    }

    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String direct = request.getHeader("X-API-Key");
        return direct == null || direct.isBlank() ? null : direct.trim();
    }
}
