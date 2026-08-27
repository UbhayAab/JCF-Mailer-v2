package com.jarurat.mailer.services;

import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.models.User;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.repositories.UserRepository;
import com.jarurat.mailer.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds the first owner account and the HR starter templates on a fresh database. */
@Component
public class BootstrapService {

    @Bean
    public ApplicationRunner seed(UserRepository userRepository,
                                  EmailTemplateRepository templateRepository,
                                  PasswordEncoder passwordEncoder,
                                  @Value("${admin.email}") String ownerEmail,
                                  @Value("${admin.password}") String ownerPassword) {
        return args -> {
            if (userRepository.count() == 0) {
                userRepository.save(new User(ownerEmail,
                        passwordEncoder.encode(ownerPassword),
                        "Account Owner", Role.OWNER, "bootstrap"));
                System.out.println("Seeded owner account: " + ownerEmail);
            }

            seedTemplate(templateRepository, "Interview Round 1 Invitation", "interview-round-1",
                    "Interview invitation: {{ROLE}} at Jarurat Care Foundation", """
                    <div style="font-family:Helvetica,Arial,sans-serif;max-width:560px;margin:0 auto;padding:28px;color:#111">
                      <h2 style="margin:0 0 14px">Hi {{CANDIDATE_NAME}},</h2>
                      <p style="color:#444;line-height:1.65">
                        Thank you for applying for the <b>{{ROLE}}</b> position at Jarurat Care Foundation.
                        We would like to invite you to the first round of interviews.
                      </p>
                      <table style="margin:22px 0;border-collapse:collapse">
                        <tr><td style="padding:6px 18px 6px 0;color:#666">Date</td><td style="padding:6px 0"><b>{{INTERVIEW_DATE}}</b></td></tr>
                        <tr><td style="padding:6px 18px 6px 0;color:#666">Time</td><td style="padding:6px 0"><b>{{INTERVIEW_TIME}}</b></td></tr>
                        <tr><td style="padding:6px 18px 6px 0;color:#666">Mode</td><td style="padding:6px 0"><b>{{INTERVIEW_MODE}}</b></td></tr>
                        <tr><td style="padding:6px 18px 6px 0;color:#666">Interviewer</td><td style="padding:6px 0"><b>{{INTERVIEWER}}</b></td></tr>
                      </table>
                      <p style="color:#444;line-height:1.65">
                        Please reply to this email to confirm. If the slot does not work for you, tell us and we will find another.
                      </p>
                      <p style="color:#444;margin-top:24px">Warm regards,<br><b>{{SENDER_NAME}}</b><br>People Team, Jarurat Care Foundation</p>
                    </div>""");

            seedTemplate(templateRepository, "Application Received", "application-received",
                    "We received your application for {{ROLE}}", """
                    <div style="font-family:Helvetica,Arial,sans-serif;max-width:560px;margin:0 auto;padding:28px;color:#111">
                      <h2 style="margin:0 0 14px">Hi {{CANDIDATE_NAME}},</h2>
                      <p style="color:#444;line-height:1.65">
                        Thank you for applying for <b>{{ROLE}}</b>. Your application is with our team and
                        we will get back to you within {{RESPONSE_DAYS}} working days.
                      </p>
                      <p style="color:#444;margin-top:24px">Warm regards,<br><b>{{SENDER_NAME}}</b><br>People Team, Jarurat Care Foundation</p>
                    </div>""");

            seedTemplate(templateRepository, "Offer Letter Follow Up", "offer-followup",
                    "Your offer from Jarurat Care Foundation", """
                    <div style="font-family:Helvetica,Arial,sans-serif;max-width:560px;margin:0 auto;padding:28px;color:#111">
                      <h2 style="margin:0 0 14px">Congratulations {{CANDIDATE_NAME}},</h2>
                      <p style="color:#444;line-height:1.65">
                        We are delighted to offer you the position of <b>{{ROLE}}</b>, starting {{START_DATE}}.
                        The formal letter is attached to a separate email from {{SENDER_NAME}}.
                      </p>
                      <p style="color:#444;line-height:1.65">Please confirm your acceptance by {{ACCEPT_BY}}.</p>
                      <p style="color:#444;margin-top:24px">Warm regards,<br><b>{{SENDER_NAME}}</b><br>People Team, Jarurat Care Foundation</p>
                    </div>""");

            /*
             * One time codes. OTP_CODE and OTP_TTL_MINUTES are injected by the OTP
             * service and can never be supplied by the caller, so a compromised
             * integration cannot email somebody a code of its own choosing.
             *
             * Deliberately plain: no images, no tracked links, no unsubscribe footer.
             * A security message that carries a tracking pixel is a security message
             * with a third party watching it, and the footer would be a lie because
             * nobody can opt out of their own login.
             */
            for (String[] otp : new String[][]{
                    {"OTP - Sign in", "otp-login", "Use this code to continue signing in to"},
                    {"OTP - Register", "otp-register", "Use this code to confirm your email address for"},
                    {"OTP - Reset password", "otp-reset-password", "Use this code to reset your password for"},
                    {"OTP - Verify email", "otp-verify-email", "Use this code to verify your email address for"},
                    {"OTP - Confirm it is you", "otp-step-up", "Use this code to confirm it is you on"}}) {
                seedTemplate(templateRepository, otp[0], otp[1],
                        "Your {{APP_NAME}} verification code", """
                        <div style="font-family:Helvetica,Arial,sans-serif;max-width:480px;margin:0 auto;padding:32px 28px;color:#111">
                          <p style="margin:0 0 18px;color:#444;line-height:1.6">
                            %s <b>{{APP_NAME}}</b>.
                          </p>
                          <div style="margin:24px 0;padding:20px;background:#f4f6f8;border-radius:8px;text-align:center">
                            <div style="font-family:Consolas,monospace;font-size:34px;letter-spacing:8px;font-weight:700;color:#111">{{OTP_CODE}}</div>
                          </div>
                          <p style="margin:0 0 8px;color:#444;line-height:1.6">
                            It expires in {{OTP_TTL_MINUTES}} minutes and can be used once.
                          </p>
                          <p style="margin:0;color:#777;font-size:13px;line-height:1.6">
                            If you did not ask for this code, ignore this email. Nobody can sign in
                            with it unless they also have your inbox.
                          </p>
                          <p style="margin:28px 0 0;color:#9ca3af;font-size:12px;border-top:1px solid #e5e7eb;padding-top:14px">
                            Jarurat Care Foundation. This is an automated security message, so it
                            has no unsubscribe link.
                          </p>
                        </div>""".formatted(otp[2]));
            }
        };
    }

    private void seedTemplate(EmailTemplateRepository repo, String name, String slug,
                              String subject, String body) {
        if (repo.existsBySlug(slug)) return;
        EmailTemplate template = new EmailTemplate(name, slug, subject, body, "TRANSACTIONAL", "bootstrap");
        template.setDescription("Starter template. Edit the copy, keep the {{MERGE_TAGS}}.");
        repo.save(template);
    }
}
