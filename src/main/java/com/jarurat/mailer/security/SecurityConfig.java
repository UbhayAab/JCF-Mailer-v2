package com.jarurat.mailer.security;

import com.jarurat.mailer.repositories.ApiKeyRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize on controller methods
public class SecurityConfig {

    /** Links inside already-delivered email must keep working, so these stay open. */
    static final String[] PUBLIC_PATHS = {
            "/", "/login", "/logout-success", "/error",
            "/css/**", "/js/**", "/logo.png", "/logo1.png", "/favicon.ico",
            "/api/mailer/click", "/api/mailer/open", "/api/mailer/unsubscribe",
            "/api/mailer/success",
            // Amazon posts here from addresses we cannot predict, so it has to be
            // open. It is not unauthenticated: SnsMessageVerifier proves the
            // payload was signed by Amazon and names one of our own topics before
            // any field of it is read.
            "/api/sns/ses-events"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Machine callers. Stateless, API key only, no CSRF because there is no
     * browser session to ride on.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiChain(HttpSecurity http, ApiKeyRepository apiKeyRepository) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyRepository), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** The human console. */
    @Bean
    @Order(2)
    public SecurityFilterChain consoleChain(HttpSecurity http) throws Exception {
        // The default XOR handler expects a BREACH-encoded value, which a fetch()
        // reading the raw cookie cannot produce. The plain handler matches what
        // the browser actually sends back.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // resolve eagerly so the cookie is always set

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler)
                        // Gmail and Yahoo one-click unsubscribe posts from the mail
                        // client, so it cannot carry a browser token. The token in the
                        // URL is the capability being checked.
                        // Amazon cannot carry our CSRF token either. The signature
                        // check is what stands in for it, and it is strictly stronger.
                        .ignoringRequestMatchers("/api/mailer/unsubscribe", "/api/sns/ses-events"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/app", true)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?loggedOut")
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                        .invalidateHttpSession(true)
                        .permitAll())
                .sessionManagement(session -> session
                        .sessionFixation(sf -> sf.newSession())
                        .maximumSessions(5))
                .headers(headers -> headers
                        .frameOptions(f -> f.sameOrigin())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                // The composer previews arbitrary customer HTML in a sandboxed
                                // iframe, so frame-src has to allow blob/data.
                                "default-src 'self'; " +
                                "img-src 'self' data: https:; " +
                                "style-src 'self' 'unsafe-inline'; " +
                                "script-src 'self' 'unsafe-inline'; " +
                                "frame-src 'self' blob: data:; " +
                                "object-src 'none'; base-uri 'self'; form-action 'self'")))
                // API callers should get a 401; browsers should get the login page.
                // Both mappings are required: with a single mapping registered,
                // Spring applies it to every request and ignores the matcher.
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                PathPatternRequestMatcher.withDefaults().matcher("/**")));
        return http.build();
    }
}
