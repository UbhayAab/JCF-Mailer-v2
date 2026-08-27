package com.jarurat.mailer.otp;

import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.models.GlobalSuppression;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.repositories.GlobalSuppressionRepository;
import com.jarurat.mailer.services.SesSender;
import com.jarurat.mailer.services.TransactionalMailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One time codes for the login system.
 *
 * Three properties drive every decision here.
 *
 * The code is never stored. It is kept as an HMAC keyed with a pepper that lives in
 * the environment rather than the database, and bound to the challenge's own id and
 * address. Six digits is twenty bits, so no work factor could protect it from an
 * attacker holding the table; keeping the key out of the table is what does.
 *
 * The answer to "request a code" never reveals anything. A real address, an unknown
 * address, a bounced address and an address over its limit all get the same 202 with
 * the same shape, and the response is padded so the timing does not give it away
 * either. Whether a message actually went out is recorded on the row, where only an
 * operator can see it.
 *
 * The limits live in the database. Every request writes a challenge row, so counting
 * rows over a window is an exact limit that costs no extra state and cannot be reset
 * by restarting the process. Only the per-IP burst check is in memory, because an IP
 * is weak evidence anyway and the limits that actually bound the damage are the
 * per-address and per-key ones.
 */
@Service
public class OtpService {

    // Digits only. Everyone has typed a six digit code before, and on a phone keypad
    // that matters more than the extra entropy a mixed alphabet would buy. The attempt
    // counter, not the code length, is what makes guessing hopeless.
    private static final String DIGITS = "0123456789";

    /** Crockford base32 minus the letters that look like digits, and minus U. */
    private static final String ALNUM = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final Set<String> PURPOSES =
            Set.of("LOGIN", "REGISTER", "RESET_PASSWORD", "VERIFY_EMAIL", "STEP_UP");

    private static final int MAX_ATTEMPTS = 5;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_SENDS_PER_CHALLENGE = 3;
    private static final int PER_EMAIL_PER_15_MIN = 3;
    private static final int PER_EMAIL_PER_DAY = 10;
    private static final int PER_KEY_PER_MINUTE = 60;
    private static final int PER_IP_PER_HOUR = 20;
    private static final int GLOBAL_PER_HOUR = 500;
    private static final int LOCKOUT_AFTER_FAILURES = 10;
    private static final int LOCKOUT_MINUTES = 30;

    /** Every request takes at least this long, so "no send performed" cannot be timed. */
    private static final long MIN_RESPONSE_MILLIS = 150;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpChallengeRepository challenges;
    private final OtpLockoutRepository lockouts;
    private final EmailTemplateRepository templates;
    private final GlobalSuppressionRepository suppressions;
    private final TransactionalMailService transactional;
    private final SesSender ses;
    private final byte[] pepper;

    /** IP and global counters. Deliberately in memory; see the class comment. */
    private final Map<String, int[]> ipWindow = new ConcurrentHashMap<>();
    private final AtomicInteger globalHour = new AtomicInteger();
    private volatile long globalHourStartedAt = System.currentTimeMillis();

    public OtpService(OtpChallengeRepository challenges,
                      OtpLockoutRepository lockouts,
                      EmailTemplateRepository templates,
                      GlobalSuppressionRepository suppressions,
                      TransactionalMailService transactional,
                      SesSender ses,
                      @Value("${otp.pepper:}") String configuredPepper) {
        this.challenges = challenges;
        this.lockouts = lockouts;
        this.templates = templates;
        this.suppressions = suppressions;
        this.transactional = transactional;
        this.ses = ses;

        if (configuredPepper == null || configuredPepper.isBlank()) {
            // A generated pepper is fine for a single process, and it is far better
            // than a hardcoded default that would be identical on every install. The
            // cost is that a restart invalidates outstanding codes, which for a ten
            // minute lifetime is an acceptable trade and is logged so it is not a
            // surprise. Set OTP_PEPPER to make it survive restarts.
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            this.pepper = generated;
            System.out.println("OTP: no otp.pepper configured, using a per-process key. "
                    + "Outstanding codes will not survive a restart. Set OTP_PEPPER to fix that.");
        } else {
            this.pepper = configuredPepper.getBytes(StandardCharsets.UTF_8);
        }
    }

    // ==================================================================
    // Requesting
    // ==================================================================

    /** What the caller gets back. Identical in shape whatever actually happened. */
    public record Issued(String challengeId, LocalDateTime expiresAt,
                         LocalDateTime resendAvailableAt, int codeLength) {}

    public record VerifyResult(boolean verified, String errorCode, String message,
                               Integer attemptsRemaining, Long retryAfterSeconds,
                               String email, String purpose, String verificationToken,
                               LocalDateTime verificationTokenExpiresAt) {

        static VerifyResult ok(OtpChallenge c, String token, LocalDateTime tokenExpiry) {
            return new VerifyResult(true, null, "Verified.", null, null,
                    c.getEmail(), c.getPurpose(), token, tokenExpiry);
        }

        static VerifyResult fail(String code, String message, Integer attemptsLeft, Long retryAfter) {
            return new VerifyResult(false, code, message, attemptsLeft, retryAfter, null, null, null, null);
        }
    }

    /**
     * Issues a code and tries to send it.
     *
     * Always returns an Issued. A refusal to send is recorded on the row and is
     * invisible to the caller, because a caller who can tell "we sent it" from "we
     * did not" can enumerate the user base one address at a time.
     */
    @Transactional
    public Issued request(String rawEmail, String rawPurpose, String templateSlug, String format,
                          Integer ttlSeconds, Map<String, String> extraData,
                          String apiKeyName, String requestIp) {
        long startedAt = System.currentTimeMillis();
        try {
            String email = normaliseEmail(rawEmail);
            String purpose = normalisePurpose(rawPurpose);
            LocalDateTime now = LocalDateTime.now();

            // The two limits that are visible to the caller are the ones that reveal
            // nothing about any individual: how hard this key is hitting us, and how
            // hard this address is. The per-address limit stays silent.
            enforceKeyLimit(apiKeyName, now);
            enforceIpLimit(requestIp);

            int ttl = ttlSeconds == null ? 600 : Math.max(60, Math.min(1800, ttlSeconds));
            boolean alnum = "alnum8".equalsIgnoreCase(format);
            String alphabet = alnum ? ALNUM : DIGITS;
            int length = alnum ? 8 : 6;
            String code = generateCode(alphabet, length);
            String publicId = "otp_" + randomToken(16);
            String slug = resolveTemplate(templateSlug, purpose);

            OtpChallenge challenge = new OtpChallenge(publicId, email, purpose,
                    hash(publicId, email, code), alnum ? "alnum8" : "digits6",
                    slug, apiKeyName, now.plusSeconds(ttl), requestIp);

            String refusal = whyNotToSend(email, purpose, now);
            if (refusal != null) {
                challenge.setSendStatus(refusal);
            } else {
                deliver(challenge, code, ttl, extraData);
            }
            challenges.save(challenge);

            return new Issued(publicId, challenge.getExpiresAt(),
                    now.plusSeconds(RESEND_COOLDOWN_SECONDS), length);
        } finally {
            padResponse(startedAt);
        }
    }

    /**
     * Why this particular request will not put a message on the wire. Null means send.
     * The reasons are separated from the response on purpose.
     */
    private String whyNotToSend(String email, String purpose, LocalDateTime now) {
        OtpLockout lockout = lockouts.findById(email).orElse(null);
        if (lockout != null && lockout.isLocked(now)) return "BLOCKED_RATE";

        if (challenges.countForEmailSince(email, purpose, now.minusMinutes(15)) >= PER_EMAIL_PER_15_MIN)
            return "BLOCKED_RATE";
        if (challenges.countForEmailSince(email, purpose, now.minusDays(1)) >= PER_EMAIL_PER_DAY)
            return "BLOCKED_RATE";

        /*
         * A marketing unsubscribe must never lock somebody out of their own account,
         * so only a hard bounce or a spam complaint blocks an OTP. That is the same
         * rule TransactionalMailService applies, and it is the right one: the two are
         * different kinds of consent.
         */
        GlobalSuppression suppressed = suppressions.findById(email).orElse(null);
        if (suppressed != null && ("BOUNCE".equals(suppressed.getReason())
                || "COMPLAINT".equals(suppressed.getReason()))) {
            return "SUPPRESSED";
        }

        if (!withinGlobalHourlyBudget()) return "BLOCKED_RATE";
        return null;
    }

    private void deliver(OtpChallenge challenge, String code, int ttlSeconds,
                         Map<String, String> extraData) {
        Map<String, String> merge = new LinkedHashMap<>();
        if (extraData != null) {
            extraData.forEach((k, v) -> {
                if (k == null || v == null || merge.size() >= 20) return;
                merge.put(k.toUpperCase(Locale.ROOT), v.length() > 200 ? v.substring(0, 200) : v);
            });
        }
        merge.putIfAbsent("APP_NAME", "Jarurat Care");
        // The caller may not override these two, whatever they sent.
        merge.put("OTP_CODE", code);
        merge.put("OTP_TTL_MINUTES", String.valueOf(Math.max(1, ttlSeconds / 60)));

        var result = transactional.send(challenge.getTemplateSlug(), challenge.getEmail(), merge,
                null, "otp:" + nz(challenge.getApiKeyName()));
        if (result.sent()) {
            challenge.setSendStatus("SENT");
            challenge.setSesMessageId(result.messageId());
        } else {
            challenge.setSendStatus("FAILED");
        }
    }

    // ==================================================================
    // Verifying
    // ==================================================================

    /**
     * Checks a code.
     *
     * Wrong code, unknown challenge and already-used challenge all collapse to the
     * same answer. Telling an attacker "that code was right but already spent" would
     * confirm they had found it.
     */
    @Transactional
    public VerifyResult verify(String publicId, String rawEmail, String rawPurpose, String rawCode) {
        LocalDateTime now = LocalDateTime.now();
        String code = rawCode == null ? "" : normaliseCode(rawCode);
        if (code.isEmpty()) {
            return VerifyResult.fail("OTP_MALFORMED", "No code supplied.", null, null);
        }

        boolean byId = publicId != null && !publicId.isBlank();
        OtpChallenge challenge = byId
                ? challenges.findByPublicId(publicId.trim()).orElse(null)
                : newestLiveFor(rawEmail, rawPurpose, now);

        if (challenge == null) {
            return VerifyResult.fail("OTP_INVALID", "That code is not valid.", null, null);
        }

        OtpLockout lockout = lockouts.findById(challenge.getEmail()).orElse(null);
        if (lockout != null && lockout.isLocked(now)) {
            return VerifyResult.fail("OTP_LOCKED",
                    "Too many incorrect codes. Try again later.", 0,
                    Duration.between(now, lockout.getLockedUntil()).toSeconds());
        }

        if (challenge.isOutOfAttempts()) {
            return VerifyResult.fail("OTP_LOCKED",
                    "Too many incorrect codes for this request. Ask for a new one.", 0,
                    (long) LOCKOUT_MINUTES * 60);
        }
        if (challenge.isConsumed()) {
            return VerifyResult.fail("OTP_INVALID", "That code is not valid.", null, null);
        }
        if (challenge.isExpired(now)) {
            // Expired is only distinguishable from invalid when the caller supplied
            // the challenge id, because holding it already proves they made the
            // request. Resolving by address must not become an existence oracle.
            return byId
                    ? VerifyResult.fail("OTP_EXPIRED", "That code has expired. Ask for a new one.", null, null)
                    : VerifyResult.fail("OTP_INVALID", "That code is not valid.", null, null);
        }

        challenge.setAttempts(challenge.getAttempts() + 1);

        String expected = challenge.getCodeHash();
        String presented = hash(challenge.getPublicId(), challenge.getEmail(), code);
        boolean matches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            challenges.save(challenge);
            noteFailure(challenge.getEmail(), now);
            int left = Math.max(0, challenge.getMaxAttempts() - challenge.getAttempts());
            return VerifyResult.fail("OTP_INVALID", "That code is not valid.", left, null);
        }

        challenge.setConsumedAt(now);
        String token = "otpv_" + randomToken(24);
        challenge.setVerificationTokenHash(sha256(token));
        challenge.setVerificationTokenExpiresAt(now.plusMinutes(5));
        challenges.save(challenge);
        lockouts.findById(challenge.getEmail()).ifPresent(l -> {
            l.setFailedCount(0);
            l.setLockedUntil(now);
            lockouts.save(l);
        });

        return VerifyResult.ok(challenge, token, challenge.getVerificationTokenExpiresAt());
    }

    /**
     * Spends the proof of verification.
     *
     * This exists so a compromised front end cannot simply assert "the OTP passed".
     * The calling system redeems the token server to server, once, and only then
     * mints its own session.
     */
    @Transactional
    public VerifyResult redeem(String token) {
        if (token == null || token.isBlank()) {
            return VerifyResult.fail("OTP_MALFORMED", "No token supplied.", null, null);
        }
        LocalDateTime now = LocalDateTime.now();
        OtpChallenge challenge = challenges.findByVerificationTokenHash(sha256(token.trim())).orElse(null);
        if (challenge == null || challenge.getVerificationTokenUsedAt() != null
                || challenge.getVerificationTokenExpiresAt() == null
                || !challenge.getVerificationTokenExpiresAt().isAfter(now)) {
            return VerifyResult.fail("OTP_TOKEN_SPENT", "That token is not valid.", null, null);
        }
        challenge.setVerificationTokenUsedAt(now);
        challenges.save(challenge);
        return VerifyResult.ok(challenge, null, null);
    }

    /**
     * Sends a fresh code under the same challenge id, keeping the original expiry.
     *
     * It has to be a fresh code rather than the original one: only the HMAC is
     * stored, and the whole point of that is that we cannot recover what we sent.
     * Storing the code reversibly so it could be repeated would trade a real security
     * property for a small convenience, which is the wrong way round.
     *
     * The expiry is deliberately not extended. A resend is a second chance at the
     * same challenge, not a way to hold a login window open indefinitely. The caller
     * must tell the user the newest email is the one that works, and the response
     * says so through codeLength and a fresh resendAvailableAt.
     */
    @Transactional
    public Issued resend(String publicId, Map<String, String> extraData, String apiKeyName) {
        long startedAt = System.currentTimeMillis();
        try {
            LocalDateTime now = LocalDateTime.now();
            OtpChallenge challenge = challenges.findByPublicId(
                    publicId == null ? "" : publicId.trim()).orElse(null);

            // An unknown or dead id gets a plausible answer rather than a 404, for
            // the same reason the request path is uniform: the caller must not be
            // able to probe which challenges exist.
            if (challenge == null || challenge.isConsumed() || challenge.isExpired(now)) {
                return new Issued(publicId, now.plusMinutes(10),
                        now.plusSeconds(RESEND_COOLDOWN_SECONDS), 6);
            }

            if (challenge.getSends() >= MAX_SENDS_PER_CHALLENGE) {
                throw new OtpRateLimitException("RESEND_EXHAUSTED",
                        "This code has already been sent " + MAX_SENDS_PER_CHALLENGE
                        + " times. Ask for a new one.", 0);
            }
            long since = Duration.between(challenge.getLastSentAt(), now).toSeconds();
            if (since < RESEND_COOLDOWN_SECONDS) {
                throw new OtpRateLimitException("RESEND_TOO_SOON",
                        "A code was just sent. Wait a moment before asking again.",
                        RESEND_COOLDOWN_SECONDS - since);
            }

            boolean alnum = "alnum8".equals(challenge.getCodeFormat());
            String alphabet = alnum ? ALNUM : DIGITS;
            int length = alnum ? 8 : 6;
            String code = generateCode(alphabet, length);

            challenge.replaceCode(hash(challenge.getPublicId(), challenge.getEmail(), code));
            challenge.setSends(challenge.getSends() + 1);
            challenge.setLastSentAt(now);

            String refusal = whyNotToSend(challenge.getEmail(), challenge.getPurpose(), now);
            if (refusal != null) {
                challenge.setSendStatus(refusal);
            } else {
                long secondsLeft = Duration.between(now, challenge.getExpiresAt()).toSeconds();
                deliver(challenge, code, (int) Math.max(60, secondsLeft), extraData);
            }
            challenges.save(challenge);

            return new Issued(challenge.getPublicId(), challenge.getExpiresAt(),
                    now.plusSeconds(RESEND_COOLDOWN_SECONDS), length);
        } finally {
            padResponse(startedAt);
        }
    }

    /** Raised when a limit is hit in a way the caller is allowed to know about. */
    public static class OtpRateLimitException extends RuntimeException {
        private final String code;
        private final long retryAfterSeconds;

        public OtpRateLimitException(String code, String message, long retryAfterSeconds) {
            super(message);
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() { return code; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    // ==================================================================
    // Limits
    // ==================================================================

    private void enforceKeyLimit(String apiKeyName, LocalDateTime now) {
        if (apiKeyName == null) return;
        if (challenges.countForKeySince(apiKeyName, now.minusMinutes(1)) >= PER_KEY_PER_MINUTE) {
            throw new OtpRateLimitException("RATE_LIMITED",
                    "This key is asking for codes too quickly.", 60);
        }
    }

    private void enforceIpLimit(String ip) {
        if (ip == null || ip.isBlank()) return;
        long hour = System.currentTimeMillis() / 3_600_000L;
        int[] window = ipWindow.compute(ip, (k, existing) ->
                existing == null || existing[0] != (int) hour
                        ? new int[]{(int) hour, 0} : existing);
        if (++window[1] > PER_IP_PER_HOUR) {
            throw new OtpRateLimitException("RATE_LIMITED",
                    "Too many code requests from this address.", 3600);
        }
    }

    private boolean withinGlobalHourlyBudget() {
        long now = System.currentTimeMillis();
        if (now - globalHourStartedAt > 3_600_000L) {
            globalHourStartedAt = now;
            globalHour.set(0);
        }
        if (globalHour.incrementAndGet() > GLOBAL_PER_HOUR) {
            // Silently dropping mail has to be visible to a human, or the first anyone
            // hears of it is a user who cannot log in.
            System.err.println("OTP: global hourly send budget of " + GLOBAL_PER_HOUR
                    + " exceeded. Codes are being issued but not sent.");
            return false;
        }
        return true;
    }

    private void noteFailure(String email, LocalDateTime now) {
        OtpLockout lockout = lockouts.findById(email)
                .orElseGet(() -> new OtpLockout(email, now, null, 0));
        int failures = lockout.getFailedCount() + 1;
        lockout.setFailedCount(failures);
        lockout.setUpdatedAt(now);
        if (failures >= LOCKOUT_AFTER_FAILURES) {
            lockout.setLockedUntil(now.plusMinutes(LOCKOUT_MINUTES));
            lockout.setReason("VERIFY_FLOOD");
            lockout.setFailedCount(0);
        }
        lockouts.save(lockout);
    }

    /**
     * Floors the response time so "no send performed" and "SES call made" cannot be
     * told apart by a stopwatch. Honest about the limit: this defeats casual timing
     * analysis, not a determined attacker with a large sample. The real protection is
     * the three-per-fifteen-minutes cap, which makes enumerating any real list
     * impractical regardless.
     */
    private static void padResponse(long startedAt) {
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed >= MIN_RESPONSE_MILLIS) return;
        try {
            Thread.sleep(MIN_RESPONSE_MILLIS - elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================================
    // Housekeeping
    // ==================================================================

    /** Codes are short lived; the rows behind them are not evidence worth keeping. */
    @Scheduled(initialDelay = 600_000, fixedDelay = 86_400_000)
    public void purgeExpired() {
        try {
            int codes = challenges.deleteOlderThan(LocalDateTime.now().minusDays(30));
            int locks = lockouts.deleteExpired(LocalDateTime.now().minusDays(7));
            if (codes + locks > 0) {
                System.out.println("OTP housekeeping: removed " + codes + " old challenge(s) and "
                        + locks + " expired lockout(s).");
            }
        } catch (Exception e) {
            System.err.println("OTP housekeeping failed: " + e.getMessage());
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private OtpChallenge newestLiveFor(String email, String purpose, LocalDateTime now) {
        if (email == null || email.isBlank()) return null;
        List<OtpChallenge> live = challenges.findLive(
                normaliseEmail(email), normalisePurpose(purpose), now);
        return live.isEmpty() ? null : live.get(0);
    }

    private String resolveTemplate(String requested, String purpose) {
        if (requested != null && !requested.isBlank()) {
            EmailTemplate found = templates.findBySlug(requested.trim()).orElse(null);
            if (found == null)
                throw new IllegalArgumentException("No template with slug '" + requested.trim() + "'.");
            return found.getSlug();
        }
        String bySlug = "otp-" + purpose.toLowerCase(Locale.ROOT).replace('_', '-');
        if (templates.existsBySlug(bySlug)) return bySlug;
        return "otp-login";
    }

    /**
     * Rejection sampling rather than a plain modulo. A modulo over a 32 bit draw makes
     * the low codes very slightly likelier, and a biased code space is exactly the
     * kind of small thing that turns into a real attack given enough attempts.
     */
    static String generateCode(String alphabet, int length) {
        int bound = alphabet.length();
        int limit = Integer.MAX_VALUE - (Integer.MAX_VALUE % bound);
        StringBuilder code = new StringBuilder(length);
        while (code.length() < length) {
            int draw = RANDOM.nextInt() & Integer.MAX_VALUE;
            if (draw >= limit) continue;
            code.append(alphabet.charAt(draw % bound));
        }
        return code.toString();
    }

    /**
     * Keyed with the pepper, and bound to the row and the address, so a hash lifted
     * out of the table cannot be tested against a different row.
     */
    private String hash(String publicId, String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            byte[] out = mac.doFinal((publicId + ":" + email + ":" + code)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is required by every JVM", e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Uppercases, strips spacing, and maps the characters people misread. */
    static String normaliseCode(String raw) {
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return cleaned.replace('I', '1').replace('L', '1').replace('O', '0');
    }

    static String normaliseEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !SesSender.EMAIL_OK.matcher(email).matches())
            throw new IllegalArgumentException("That does not look like an email address.");
        return email;
    }

    static String normalisePurpose(String raw) {
        String purpose = raw == null ? "LOGIN" : raw.trim().toUpperCase(Locale.ROOT);
        if (!PURPOSES.contains(purpose))
            throw new IllegalArgumentException("Purpose must be one of " + PURPOSES + ".");
        return purpose;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
