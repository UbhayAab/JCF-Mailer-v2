package com.jarurat.mailer.services;

import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.models.EmailTemplate;
import com.jarurat.mailer.models.GlobalSuppression;
import com.jarurat.mailer.models.TransactionalLog;
import com.jarurat.mailer.repositories.EmailTemplateRepository;
import com.jarurat.mailer.repositories.GlobalSuppressionRepository;
import com.jarurat.mailer.repositories.TransactionalLogRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * One-off mail addressed by template slug. This is what the HR system calls when
 * a candidate is shortlisted or an interview is booked.
 */
@Service
public class TransactionalMailService {

    private final EmailTemplateRepository templateRepository;
    private final TransactionalLogRepository logRepository;
    private final GlobalSuppressionRepository suppressionRepository;
    private final SesSender ses;
    private final MessageLogService messageLog;

    public TransactionalMailService(EmailTemplateRepository templateRepository,
                                    TransactionalLogRepository logRepository,
                                    GlobalSuppressionRepository suppressionRepository,
                                    SesSender ses,
                                    MessageLogService messageLog) {
        this.templateRepository = templateRepository;
        this.logRepository = logRepository;
        this.suppressionRepository = suppressionRepository;
        this.ses = ses;
        this.messageLog = messageLog;
    }

    public record Result(boolean sent, String messageId, String error) {}

    public Result send(String slug, String to, Map<String, String> mergeFields,
                       String subjectOverride, String sentVia) {

        /*
         * A request that was never going to become a message does not get logged.
         *
         * Both of these used to go through fail(), which writes a transactional_log
         * row AND a message_log row. So a caller looping on a typo'd slug wrote two
         * rows per attempt, at request rate, forever, into tables on a 2GB box. The
         * message log in particular is meant to answer "what happened to the mail we
         * sent", and a request that produced no mail has no business being in it.
         *
         * The caller still gets a clear error; it is only the logging that stops.
         */
        String recipient = to == null ? "" : to.trim().toLowerCase();
        if (recipient.length() > 254 || !SesSender.EMAIL_OK.matcher(recipient).matches()) {
            return new Result(false, null, "Invalid email address");
        }

        String cleanSlug = slug == null ? "" : slug.trim();
        if (cleanSlug.isEmpty() || cleanSlug.length() > 200) {
            return new Result(false, null, "Invalid template slug");
        }

        EmailTemplate template = templateRepository.findBySlug(cleanSlug).orElse(null);
        if (template == null) {
            return new Result(false, null, "No template with slug '" + cleanSlug + "'");
        }
        slug = cleanSlug;

        /*
         * A candidate who opted out of the newsletter must still receive their
         * interview mail, so a marketing unsubscribe does not block transactional
         * sending. A hard bounce or spam complaint does, because that address is
         * either dead or hostile and SES will reject it anyway.
         */
        GlobalSuppression suppressed = suppressionRepository.findById(recipient).orElse(null);
        if (suppressed != null && ("BOUNCE".equals(suppressed.getReason())
                || "COMPLAINT".equals(suppressed.getReason()))) {
            logRepository.save(new TransactionalLog(slug, recipient, template.getSubject(),
                    "SUPPRESSED", null, "Address is on the suppression list as " + suppressed.getReason(), sentVia));
            messageLog.recordSuppressed(recipient, template.getSubject(), null,
                    "Address is on the suppression list as " + suppressed.getReason(), actorFor(sentVia));
            return new Result(false, null, "Address suppressed (" + suppressed.getReason() + ")");
        }

        String subject = subjectOverride != null && !subjectOverride.isBlank()
                ? subjectOverride : template.getSubject();

        long startedNanos = System.nanoTime();
        try {
            String html = ses.renderTransactional(template.getHtmlBody(), mergeFields);
            String resolvedSubject = ses.renderSubject(subject, mergeFields);

            String messageId = ses.send(new SesSender.Outgoing(
                    recipient, resolvedSubject, html, null, null, null));

            logRepository.save(new TransactionalLog(slug, recipient, resolvedSubject,
                    "SENT", messageId, null, sentVia));
            messageLog.recordSent(recipient, resolvedSubject, messageId, null,
                    (System.nanoTime() - startedNanos) / 1_000_000L, actorFor(sentVia));
            return new Result(true, messageId, null);
        } catch (Exception e) {
            return fail(slug, recipient, subject, e.getMessage(), sentVia, startedNanos);
        }
    }

    private Result fail(String slug, String to, String subject, String error, String sentVia) {
        return fail(slug, to, subject, error, sentVia, System.nanoTime());
    }

    private Result fail(String slug, String to, String subject, String error, String sentVia, long startedNanos) {
        logRepository.save(new TransactionalLog(slug, to, subject, "FAILED", null, error, sentVia));
        messageLog.recordFailed(to, subject, null, error,
                (System.nanoTime() - startedNanos) / 1_000_000L, actorFor(sentVia));
        return new Result(false, null, error);
    }

    /**
     * An API key send has no logged-in user, so sentVia ("api:jcf-hr" and the like)
     * is the only identity there is. Falling through to null lets the log service
     * read the console user off the security context instead.
     */
    private static String actorFor(String sentVia) {
        return sentVia == null || sentVia.isBlank() ? null : sentVia;
    }
}
