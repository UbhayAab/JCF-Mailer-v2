package com.jarurat.mailer.otp;

import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.models.GlobalSuppression;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.repositories.GlobalSuppressionRepository;
import com.jarurat.mailer.services.SesSender;
import com.jarurat.mailer.services.TransactionalMailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The security properties, checked rather than asserted in a comment.
 *
 * The code itself never leaves the service, so every test here captures it the only
 * way a real attacker could not: by intercepting the mail we send. That is also what
 * makes the negative tests meaningful, since a test that could read the stored hash
 * would be proving something weaker than the real threat model.
 */
@SpringBootTest
class OtpServiceTest {

    @Autowired OtpService otp;
    @Autowired OtpChallengeRepository challenges;
    @Autowired OtpLockoutRepository lockouts;
    @Autowired EmailTemplateRepository templates;
    @Autowired GlobalSuppressionRepository suppressions;

    @MockitoBean TransactionalMailService transactional;
    @MockitoBean SesSender ses;

    /** The code as it appeared in the message we tried to send. */
    private final AtomicReference<String> lastCode = new AtomicReference<>();
    private String email;

    @BeforeEach
    void setUp() {
        email = "user" + System.nanoTime() + "@example.com";
        lastCode.set(null);
        lockouts.deleteAll();

        if (!templates.existsBySlug("otp-login")) {
            templates.save(new EmailTemplate("OTP", "otp-login", "Your code",
                    "<p>{{OTP_CODE}}</p>", "TRANSACTIONAL", "test"));
        }
        when(transactional.send(anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(call -> {
                    Map<String, String> merge = call.getArgument(2);
                    lastCode.set(merge.get("OTP_CODE"));
                    return new TransactionalMailService.Result(true, "test-message-id", null);
                });
    }

    // ==================================================================

    @Test
    @DisplayName("a code is issued, emailed, and verifies once")
    void happyPath() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "test-key", "1.2.3.4");

        assertThat(issued.challengeId()).startsWith("otp_");
        assertThat(issued.codeLength()).isEqualTo(6);
        assertThat(lastCode.get()).matches("\\d{6}");

        OtpService.VerifyResult result = otp.verify(issued.challengeId(), null, null, lastCode.get());

        assertThat(result.verified()).isTrue();
        assertThat(result.email()).isEqualTo(email);
        assertThat(result.verificationToken()).startsWith("otpv_");
    }

    @Test
    @DisplayName("the code is never stored, only a keyed hash of it")
    void theCodeIsNeverStored() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);
        OtpChallenge stored = challenges.findByPublicId(issued.challengeId()).orElseThrow();

        assertThat(stored.getCodeHash())
                .as("a database leak must not hand anyone a working code")
                .doesNotContain(lastCode.get())
                .hasSize(64);
    }

    @Test
    @DisplayName("a correct code cannot be used twice")
    void singleUse() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);
        String code = lastCode.get();

        assertThat(otp.verify(issued.challengeId(), null, null, code).verified()).isTrue();

        OtpService.VerifyResult replay = otp.verify(issued.challengeId(), null, null, code);
        assertThat(replay.verified()).isFalse();
        assertThat(replay.errorCode())
                .as("saying \"already used\" would confirm to an attacker that they had found it")
                .isEqualTo("OTP_INVALID");
    }

    @Test
    @DisplayName("attempts run out and then even the right code is refused")
    void attemptsAreExhausted() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);
        String realCode = lastCode.get();

        for (int i = 1; i <= 5; i++) {
            OtpService.VerifyResult wrong = otp.verify(issued.challengeId(), null, null, "000000");
            assertThat(wrong.verified()).isFalse();
            assertThat(wrong.attemptsRemaining()).isEqualTo(5 - i);
        }

        OtpService.VerifyResult tooLate = otp.verify(issued.challengeId(), null, null, realCode);
        assertThat(tooLate.verified()).isFalse();
        assertThat(tooLate.errorCode()).isEqualTo("OTP_LOCKED");
    }

    @Test
    @DisplayName("an expired code is refused, and only says so when the caller holds the id")
    void expiryIsNotAnOracle() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, 60, null, "k", null);
        OtpChallenge stored = challenges.findByPublicId(issued.challengeId()).orElseThrow();
        expire(stored);

        assertThat(otp.verify(issued.challengeId(), null, null, lastCode.get()).errorCode())
                .as("holding the challenge id already proves they made the request")
                .isEqualTo("OTP_EXPIRED");

        assertThat(otp.verify(null, email, "LOGIN", lastCode.get()).errorCode())
                .as("resolving by address must not reveal whether a challenge ever existed")
                .isEqualTo("OTP_INVALID");
    }

    @Test
    @DisplayName("an unknown address gets the same answer as a real one")
    void enumerationIsNotPossible() {
        OtpService.Issued real = otp.request(email, "LOGIN", null, null, null, null, "k", null);
        OtpService.Issued unknown = otp.request(
                "nobody" + System.nanoTime() + "@example.com", "LOGIN", null, null, null, null, "k", null);

        assertThat(unknown.challengeId()).startsWith("otp_");
        assertThat(unknown.codeLength()).isEqualTo(real.codeLength());
        assertThat(unknown.expiresAt()).isNotNull();
    }

    @Test
    @DisplayName("a bounced address is refused silently, and the caller cannot tell")
    void bouncedAddressIsRefusedWithoutSaying() {
        suppressions.save(new GlobalSuppression(email, "BOUNCE"));

        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);

        assertThat(issued.challengeId()).startsWith("otp_");
        assertThat(lastCode.get()).as("nothing should have gone on the wire").isNull();
        assertThat(challenges.findByPublicId(issued.challengeId()).orElseThrow().getSendStatus())
                .as("the refusal is recorded where only an operator can see it")
                .isEqualTo("SUPPRESSED");
    }

    @Test
    @DisplayName("a marketing unsubscribe never blocks a login code")
    void unsubscribeDoesNotBlockOtp() {
        suppressions.save(new GlobalSuppression(email, "UNSUBSCRIBED"));

        otp.request(email, "LOGIN", null, null, null, null, "k", null);

        assertThat(lastCode.get())
                .as("opting out of a newsletter must not lock somebody out of their own account")
                .isNotNull();
    }

    @Test
    @DisplayName("a fourth request in fifteen minutes is issued but not sent")
    void perAddressLimitIsSilent() {
        for (int i = 0; i < 3; i++) {
            otp.request(email, "LOGIN", null, null, null, null, "k", null);
            assertThat(lastCode.get()).isNotNull();
            lastCode.set(null);
        }

        OtpService.Issued fourth = otp.request(email, "LOGIN", null, null, null, null, "k", null);

        assertThat(fourth.challengeId()).as("the response is indistinguishable").startsWith("otp_");
        assertThat(lastCode.get()).as("but no fourth message went out").isNull();
        assertThat(challenges.findByPublicId(fourth.challengeId()).orElseThrow().getSendStatus())
                .isEqualTo("BLOCKED_RATE");
    }

    @Test
    @DisplayName("a resend mints a new code and retires the old one")
    void resendReplacesTheCode() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);
        String first = lastCode.get();

        // Past the cooldown, without waiting a real minute.
        OtpChallenge stored = challenges.findByPublicId(issued.challengeId()).orElseThrow();
        stored.setLastSentAt(stored.getLastSentAt().minusMinutes(5));
        challenges.save(stored);

        otp.resend(issued.challengeId(), null, "k");
        String second = lastCode.get();

        assertThat(second).isNotEqualTo(first);
        assertThat(otp.verify(issued.challengeId(), null, null, first).verified())
                .as("only the hash was kept, so the original code is genuinely unrecoverable")
                .isFalse();
        assertThat(otp.verify(issued.challengeId(), null, null, second).verified()).isTrue();
    }

    @Test
    @DisplayName("a resend inside the cooldown is refused out loud")
    void resendCooldown() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);

        assertThatRateLimited(() -> otp.resend(issued.challengeId(), null, "k"), "RESEND_TOO_SOON");
    }

    @Test
    @DisplayName("the verification token is single use")
    void verificationTokenIsSpentOnce() {
        OtpService.Issued issued = otp.request(email, "LOGIN", null, null, null, null, "k", null);
        String token = otp.verify(issued.challengeId(), null, null, lastCode.get()).verificationToken();

        assertThat(otp.redeem(token).verified()).isTrue();
        assertThat(otp.redeem(token).errorCode())
                .as("a stolen token must not be replayable")
                .isEqualTo("OTP_TOKEN_SPENT");
    }

    @Test
    @DisplayName("codes are drawn without modulo bias")
    void codesAreUnbiased() {
        // Rejection sampling means every digit is equally likely. A plain modulo over
        // a 32 bit draw would make the low digits very slightly likelier, and a biased
        // code space is exactly the sort of small thing that becomes an attack.
        int[] seen = new int[10];
        for (int i = 0; i < 6000; i++) {
            for (char c : OtpService.generateCode("0123456789", 6).toCharArray()) seen[c - '0']++;
        }
        for (int digit = 0; digit < 10; digit++) {
            assertThat(seen[digit]).as("digit " + digit).isBetween(3200, 4000);
        }
    }

    @Test
    @DisplayName("codes are unique across many draws")
    void codesDoNotRepeatMeaningfully() {
        Set<String> drawn = new LinkedHashSet<>();
        for (int i = 0; i < 500; i++) drawn.add(OtpService.generateCode("0123456789", 6));
        assertThat(drawn.size()).isGreaterThan(490);
    }

    @Test
    @DisplayName("what people mistype is normalised before comparison")
    void codeNormalisation() {
        assertThat(OtpService.normaliseCode(" 483 920 ")).isEqualTo("483920");
        assertThat(OtpService.normaliseCode("4839-20")).isEqualTo("483920");
        // O for zero and I or L for one are the classic misreads in an alphanumeric code.
        assertThat(OtpService.normaliseCode("abcO1")).isEqualTo("ABC01");
        assertThat(OtpService.normaliseCode("aIcL")).isEqualTo("A1C1");
    }

    @Test
    @DisplayName("a nonsense address or purpose is refused up front")
    void inputValidation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> otp.request("not-an-email", "LOGIN", null, null, null, null, "k", null))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> otp.request(email, "TAKE_OVER_ACCOUNT", null, null, null, null, "k", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the caller cannot inject its own code through the merge data")
    void callerCannotChooseTheCode() {
        otp.request(email, "LOGIN", null, null, null,
                Map.of("OTP_CODE", "000000", "APP_NAME", "Jarurat ID"), "k", null);

        assertThat(lastCode.get())
                .as("a compromised integration must not be able to email somebody a code it picked")
                .isNotEqualTo("000000");
    }

    // ------------------------------------------------------------------

    private void expire(OtpChallenge stored) {
        // The expiry column is not settable from outside, which is the point, so the
        // test moves the clock the same way the world does: through the repository.
        challenges.delete(stored);
        OtpChallenge expired = new OtpChallenge(stored.getPublicId(), stored.getEmail(),
                stored.getPurpose(), stored.getCodeHash(), stored.getCodeFormat(),
                stored.getTemplateSlug(), stored.getApiKeyName(),
                java.time.LocalDateTime.now().minusSeconds(1), stored.getRequestIp());
        challenges.save(expired);
    }

    private void assertThatRateLimited(Runnable action, String expectedCode) {
        try {
            action.run();
            org.assertj.core.api.Assertions.fail("expected a rate limit for " + expectedCode);
        } catch (OtpService.OtpRateLimitException e) {
            assertThat(e.getCode()).isEqualTo(expectedCode);
        }
    }
}
