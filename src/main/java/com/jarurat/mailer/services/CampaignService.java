package com.jarurat.mailer.services;

import com.jarurat.mailer.campaignsplus.SafetyCheckService;
import com.jarurat.mailer.merge.MergeTags;
import com.jarurat.mailer.messagelog.MessageLogService;
import com.jarurat.mailer.models.*;
import com.jarurat.mailer.repositories.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignRecipientRepository recipientRepository;
    private final SubscriberRepository subscriberRepository;
    private final ClickEventRepository clickEventRepository;
    private final MailingListRepository listRepository;
    private final ListMemberRepository listMemberRepository;
    private final SuppressionService suppression;
    private final SesSender ses;
    private final MessageLogService messageLog;
    private final SafetyCheckService safety;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrencyGate;
    private final Map<Long, Progress> liveRuns = new ConcurrentHashMap<>();

    public CampaignService(CampaignRepository campaignRepository,
                           CampaignRecipientRepository recipientRepository,
                           SubscriberRepository subscriberRepository,
                           ClickEventRepository clickEventRepository,
                           MailingListRepository listRepository,
                           ListMemberRepository listMemberRepository,
                           SuppressionService suppression,
                           SesSender ses,
                           MessageLogService messageLog,
                           SafetyCheckService safety,
                           @Value("${aws.ses.maxConcurrency:16}") int maxConcurrency) {
        this.campaignRepository = campaignRepository;
        this.recipientRepository = recipientRepository;
        this.subscriberRepository = subscriberRepository;
        this.clickEventRepository = clickEventRepository;
        this.listRepository = listRepository;
        this.listMemberRepository = listMemberRepository;
        this.suppression = suppression;
        this.ses = ses;
        this.messageLog = messageLog;
        this.safety = safety;
        this.concurrencyGate = new Semaphore(Math.max(1, maxConcurrency));
    }

    public static class Progress {
        public volatile String status = "SENDING";
        public final AtomicInteger sent = new AtomicInteger();
        public final AtomicInteger failed = new AtomicInteger();
        public final AtomicInteger skipped = new AtomicInteger();
        public volatile int total;
        public volatile long startedAt = System.currentTimeMillis();
        public volatile String lastError;
    }

    public Progress getProgress(Long campaignId) { return liveRuns.get(campaignId); }

    // ------------------------------------------------------------------
    // Queueing
    // ------------------------------------------------------------------

    /** Snapshots the target list into campaign_recipient. Safe to re-run. */
    public int queueAudience(Long campaignId) {
        Campaign campaign = require(campaignId);
        if (campaign.getListId() == null)
            throw new IllegalArgumentException("Point the campaign at a list first.");
        if (!listRepository.existsById(campaign.getListId()))
            throw new IllegalArgumentException("That list no longer exists.");

        int queued = recipientRepository.queueFromList(campaignId, campaign.getListId());
        campaign.setTotalRecipients((int) recipientRepository.countByCampaignId(campaignId));
        campaignRepository.save(campaign);
        return queued;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    public Progress send(Long campaignId) {
        Campaign campaign = require(campaignId);
        validateSendable(campaign);

        Progress running = liveRuns.get(campaignId);
        if (running != null && "SENDING".equals(running.status))
            throw new IllegalStateException("This campaign is already sending.");

        queueAudience(campaignId);

        int pending = (int) recipientRepository.countByCampaignIdAndStatus(campaignId, "PENDING");
        if (pending == 0)
            throw new IllegalStateException("Nobody is queued. The list may be empty or fully suppressed.");

        // Runs here rather than inside dispatchAll so a blocked campaign is refused
        // on the caller's thread and never leaves DRAFT. Inside the dispatcher it
        // would flip to SENDING and then to FAILED, which reads like a send that broke
        // rather than one we declined to start. The scheduler calls send() too, so
        // scheduled blasts get the same gate.
        safety.assertSafeToSend(campaign);

        Progress progress = new Progress();
        progress.total = pending;
        liveRuns.put(campaignId, progress);

        campaign.setStatus("SENDING");
        campaign.setStartedAt(LocalDateTime.now());
        campaignRepository.save(campaign);

        // The dispatcher's virtual threads do not inherit the SecurityContext, so the
        // actor has to be read here or every logged row would be attributed to "system".
        String actor = AuditService.currentActor();

        System.out.println("Campaign " + campaign.getName() + " starting, " + pending + " recipients.");
        workers.submit(() -> dispatchAll(campaign, progress, actor));
        return progress;
    }

    private void validateSendable(Campaign campaign) {
        if (isBlank(campaign.getSubject())) throw new IllegalArgumentException("Add a subject line first.");
        if (isBlank(campaign.getHtmlBody())) throw new IllegalArgumentException("The email body is empty.");
        if (campaign.getListId() == null) throw new IllegalArgumentException("Choose an audience list first.");
    }

    private void dispatchAll(Campaign campaign, Progress progress, String actor) {
        try {
            // Recipients leave the PENDING set as they are processed, so draining
            // the first page repeatedly terminates and never loads 50k rows at once.
            while (true) {
                List<CampaignRecipient> batch = recipientRepository.findByCampaignIdAndStatus(
                        campaign.getId(), "PENDING", PageRequest.of(0, 500));
                if (batch.isEmpty()) break;

                List<Future<?>> inFlight = new ArrayList<>(batch.size());
                for (CampaignRecipient recipient : batch) {
                    inFlight.add(workers.submit(() -> sendOne(recipient, campaign, progress, actor)));
                }
                for (Future<?> f : inFlight) {
                    try { f.get(); } catch (Exception ignored) {}
                }
            }

            progress.status = "SENT";
            campaign.setStatus("SENT");
            campaign.setCompletedAt(LocalDateTime.now());
            campaign.setSentCount((int) recipientRepository.countByCampaignIdAndStatus(campaign.getId(), "SENT"));
            campaign.setFailedCount((int) recipientRepository.countByCampaignIdAndStatus(campaign.getId(), "FAILED"));
            campaignRepository.save(campaign);

            System.out.println("Campaign " + campaign.getName() + " done. sent=" + progress.sent.get()
                    + " failed=" + progress.failed.get() + " skipped=" + progress.skipped.get());
        } catch (Exception e) {
            progress.status = "FAILED";
            progress.lastError = e.getMessage();
            campaign.setStatus("FAILED");
            campaignRepository.save(campaign);
            System.err.println("Campaign " + campaign.getName() + " aborted: " + e);
        }
    }

    private void sendOne(CampaignRecipient recipient, Campaign campaign, Progress progress, String actor) {
        try {
            concurrencyGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long startedNanos = System.nanoTime();
        try {
            // Someone may have unsubscribed between the snapshot and this moment.
            if (suppression.isSuppressed(recipient.getEmail())) {
                recipient.setStatus("SKIPPED");
                recipientRepository.save(recipient);
                progress.skipped.incrementAndGet();
                messageLog.recordSuppressed(recipient.getEmail(), campaign.getSubject(), campaign.getId(),
                        "Address is on the suppression list", actor);
                return;
            }

            // The subscriber row was already being loaded a few lines below to bump
            // totalSent. Reading it here instead costs nothing extra and is what
            // lets LAST_NAME, PHONE and COMPANY resolve at all: before this the map
            // held three keys, so a creative using {{COMPANY}} shipped a blank to
            // every recipient however well populated the subscriber table was.
            Subscriber subscriber = subscriberRepository.findById(recipient.getSubscriberId()).orElse(null);

            Map<String, String> merge = mergeFieldsFor(recipient, subscriber);

            String html = ses.renderMarketing(campaign.getHtmlBody(), recipient.getToken(), merge,
                    campaign.getPreheader(), campaign.isTrackOpens(), campaign.isTrackClicks());

            // The subject has to go through the same substitution as the body. It
            // did not, so a subject line reading "{{FIRST_NAME}}, your slot is
            // confirmed" reached every inbox with the braces still in it, while the
            // test send and the preview both rendered it correctly and hid the bug.
            String subject = ses.renderSubject(campaign.getSubject(), merge);

            String messageId = ses.send(new SesSender.Outgoing(
                    recipient.getEmail(), subject, html,
                    campaign.getFromName(), campaign.getReplyTo(),
                    ses.getAppDomain() + "/api/mailer/unsubscribe?token=" + recipient.getToken()));

            recipient.setStatus("SENT");
            recipient.setSentAt(LocalDateTime.now());
            recipient.setMessageId(messageId);
            recipientRepository.save(recipient);

            if (subscriber != null) {
                subscriber.setTotalSent(subscriber.getTotalSent() + 1);
                subscriberRepository.save(subscriber);
            }

            progress.sent.incrementAndGet();
            messageLog.recordSent(recipient.getEmail(), subject, messageId,
                    campaign.getId(), millisSince(startedNanos), actor);
        } catch (Exception e) {
            recipient.setStatus("FAILED");
            recipient.setFailReason(e.getMessage());
            recipientRepository.save(recipient);
            progress.failed.incrementAndGet();
            progress.lastError = e.getMessage();
            messageLog.recordFailed(recipient.getEmail(), campaign.getSubject(), campaign.getId(),
                    e.getMessage(), millisSince(startedNanos), actor);
            System.err.println("Send failed for " + recipient.getEmail() + ": " + e.getMessage());
        } finally {
            concurrencyGate.release();
        }
    }

    /**
     * A test send with the caller's own merge values.
     *
     * This used to hardcode "Dr. Sharma" for NAME and FIRST_NAME and nothing else,
     * which meant the one thing a test is for - checking that the personalisation
     * lands - was the one thing it could not show you. A creative using
     * {{HOSPITAL}} rendered a blank in the test and a blank in the blast, and
     * looked identical either way.
     *
     * Supplied values win. Any tag left unsupplied falls back to a recognisable
     * sample rather than a blank, because a blank in a test is ambiguous: it reads
     * the same as a tag the sender failed to substitute.
     */
    public String sendTest(Long campaignId, String to, Map<String, String> suppliedMerge) {
        Campaign campaign = require(campaignId);
        if (isBlank(campaign.getHtmlBody())) throw new IllegalArgumentException("The email body is empty.");
        String recipient = to == null ? "" : to.trim();
        if (!SesSender.EMAIL_OK.matcher(recipient).matches())
            throw new IllegalArgumentException("That does not look like an email address.");

        String token = "test-" + UUID.randomUUID();
        Map<String, String> merge = testMergeFields(campaign, recipient, suppliedMerge);
        String html = ses.renderMarketing(campaign.getHtmlBody(), token, merge,
                campaign.getPreheader(), false, false);

        // The subject carries tags too, and a test that shows the raw {{FIRST_NAME}}
        // in the inbox list cannot answer "does the subject personalisation work".
        String subject = "[TEST] " + ses.renderSubject(campaign.getSubject(), merge);
        long startedNanos = System.nanoTime();
        try {
            String messageId = ses.send(new SesSender.Outgoing(recipient, subject, html,
                    campaign.getFromName(), campaign.getReplyTo(), null));
            messageLog.recordSent(recipient, subject, messageId, campaign.getId(),
                    millisSince(startedNanos), null);
            return messageId;
        } catch (RuntimeException e) {
            messageLog.recordFailed(recipient, subject, campaign.getId(),
                    e.getMessage(), millisSince(startedNanos), null);
            throw e;
        }
    }

    /** Exactly what goes on the wire, so tracking can be confirmed before a blast. */
    public String previewRendered(Long campaignId, Map<String, String> suppliedMerge) {
        Campaign campaign = require(campaignId);
        Map<String, String> merge = testMergeFields(campaign, "sample@example.com", suppliedMerge);
        return ses.renderMarketing(nz(campaign.getHtmlBody()), "sample-token", merge,
                campaign.getPreheader(), campaign.isTrackOpens(), campaign.isTrackClicks());
    }

    /** The merge tags this campaign actually uses, subject and preheader included. */
    public List<String> mergeTagsOf(Long campaignId) {
        Campaign campaign = require(campaignId);
        return MergeTags.extract(campaign.getSubject(), campaign.getPreheader(), campaign.getHtmlBody());
    }

    /**
     * Supplied values first, a sample for every tag the caller left out, and the
     * three tags every campaign is entitled to assume exist.
     */
    private Map<String, String> testMergeFields(Campaign campaign, String recipient,
                                                Map<String, String> supplied) {
        Map<String, String> merge = new LinkedHashMap<>(MergeTags.sampleMap(
                recipient, campaign.getSubject(), campaign.getPreheader(), campaign.getHtmlBody()));
        merge.putIfAbsent("NAME", "Dr. Akanksha Chichra");
        merge.putIfAbsent("FIRST_NAME", "Dr. Akanksha");
        merge.put("EMAIL", recipient);
        if (supplied != null) {
            supplied.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    merge.put(key.toUpperCase(java.util.Locale.ROOT), value);
                }
            });
        }
        return merge;
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    /** Fires anything whose scheduled time has arrived. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void runDueCampaigns() {
        try {
            List<Campaign> due = campaignRepository.findByStatusAndScheduledAtLessThanEqual(
                    "SCHEDULED", LocalDateTime.now());
            for (Campaign campaign : due) {
                try {
                    System.out.println("Scheduled campaign firing: " + campaign.getName());
                    send(campaign.getId());
                } catch (Exception e) {
                    campaign.setStatus("FAILED");
                    campaignRepository.save(campaign);
                    System.err.println("Scheduled campaign " + campaign.getName() + " failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Schedule scan failed: " + e.getMessage());
        }
    }

    public void schedule(Long campaignId, LocalDateTime when) {
        Campaign campaign = require(campaignId);
        validateSendable(campaign);
        if (when.isBefore(LocalDateTime.now()))
            throw new IllegalArgumentException("That time is already in the past.");
        campaign.setScheduledAt(when);
        campaign.setStatus("SCHEDULED");
        campaignRepository.save(campaign);
    }

    public void cancelSchedule(Long campaignId) {
        Campaign campaign = require(campaignId);
        if (!"SCHEDULED".equals(campaign.getStatus()))
            throw new IllegalStateException("That campaign is not scheduled.");
        campaign.setScheduledAt(null);
        campaign.setStatus("DRAFT");
        campaignRepository.save(campaign);
    }

    public int requeueFailed(Long campaignId) {
        return recipientRepository.requeueFailed(campaignId);
    }

    // ------------------------------------------------------------------
    // Engagement
    // ------------------------------------------------------------------

    public void trackOpen(String token) {
        recipientRepository.findByToken(token).ifPresent(r -> {
            if (r.getOpenedAt() == null) r.setOpenedAt(LocalDateTime.now());
            r.setOpenCount(r.getOpenCount() + 1);
            recipientRepository.save(r);
            subscriberRepository.findById(r.getSubscriberId()).ifPresent(s -> {
                s.setTotalOpened(s.getTotalOpened() + 1);
                s.setLastEngagedAt(LocalDateTime.now());
                subscriberRepository.save(s);
            });
        });
    }

    public String trackClick(String token, String url) {
        recipientRepository.findByToken(token).ifPresent(r -> {
            r.setLastClickedAt(LocalDateTime.now());
            r.setClickCount(r.getClickCount() + 1);
            if (r.getOpenedAt() == null) r.setOpenedAt(LocalDateTime.now()); // a click implies an open
            recipientRepository.save(r);
            clickEventRepository.save(new ClickEvent(r.getCampaignId(), r.getSubscriberId(), r.getEmail(), url));
            subscriberRepository.findById(r.getSubscriberId()).ifPresent(s -> {
                s.setTotalClicked(s.getTotalClicked() + 1);
                s.setLastEngagedAt(LocalDateTime.now());
                subscriberRepository.save(s);
            });
        });
        return url;
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    public Map<String, Object> stats(Campaign campaign) {
        Long id = campaign.getId();
        long total = recipientRepository.countByCampaignId(id);
        long sent = recipientRepository.countByCampaignIdAndStatus(id, "SENT");
        long pending = recipientRepository.countByCampaignIdAndStatus(id, "PENDING");
        long failed = recipientRepository.countByCampaignIdAndStatus(id, "FAILED");
        long skipped = recipientRepository.countByCampaignIdAndStatus(id, "SKIPPED");
        long opened = recipientRepository.countByCampaignIdAndOpenedAtIsNotNull(id);
        long clicked = recipientRepository.countByCampaignIdAndLastClickedAtIsNotNull(id);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", campaign.getName());
        m.put("subject", nz(campaign.getSubject()));
        m.put("status", campaign.getStatus());
        m.put("listId", campaign.getListId());
        m.put("listName", campaign.getListId() == null ? null
                : listRepository.findById(campaign.getListId()).map(MailingList::getName).orElse("(deleted list)"));
        m.put("createdAt", campaign.getCreatedAt() == null ? "" : campaign.getCreatedAt().toString());
        m.put("scheduledAt", campaign.getScheduledAt() == null ? null : campaign.getScheduledAt().toString());
        m.put("completedAt", campaign.getCompletedAt() == null ? null : campaign.getCompletedAt().toString());
        m.put("createdBy", nz(campaign.getCreatedBy()));
        m.put("total", total);
        m.put("sent", sent);
        m.put("pending", pending);
        m.put("failed", failed);
        m.put("skipped", skipped);
        m.put("opened", opened);
        m.put("clicked", clicked);
        m.put("openRate", rate(opened, sent));
        m.put("clickRate", rate(clicked, sent));
        m.put("clickToOpenRate", rate(clicked, opened));
        m.put("editable", campaign.isEditable());
        return m;
    }

    public List<Map<String, Object>> linkBreakdown(Long campaignId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClickEventRepository.LinkStat s : clickEventRepository.linkBreakdown(campaignId)) {
            out.add(Map.of("url", nz(s.getUrl()), "clicks", s.getClicks(), "unique", s.getUniqueClicks()));
        }
        return out;
    }

    public long mailableSize(Long listId) { return listMemberRepository.countMailable(listId); }

    public Campaign require(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found."));
    }

    /**
     * The merge tags a real campaign send can fill, and where each value comes from.
     *
     * This is the honest answer to "will {{HOSPITAL}} work". Anything outside this
     * set renders blank for every recipient no matter what the source spreadsheet
     * held, because campaign_recipient and subscriber between them have nowhere to
     * put an arbitrary column. The composer reads this to warn before a blast
     * instead of leaving people to find out from the blast.
     *
     * Keep it in step with mergeFieldsFor below. A key here that mergeFieldsFor
     * does not set is a promise the sender does not keep.
     */
    public static final Map<String, String> SENDABLE_FIELDS;
    static {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("EMAIL", "the recipient address");
        fields.put("NAME", "the subscriber's full name");
        fields.put("FIRST_NAME", "first word of the name, honorific kept");
        fields.put("LAST_NAME", "the subscriber's last name");
        fields.put("PHONE", "the subscriber's phone number");
        fields.put("COMPANY", "the subscriber's company or hospital");
        SENDABLE_FIELDS = Collections.unmodifiableMap(fields);
    }

    /**
     * One recipient's merge values. The subscriber may be null when the row was
     * deleted between the snapshot and the send, in which case the two fields
     * carried on campaign_recipient itself still resolve and the rest blank out,
     * which is better than failing the send over a missing surname.
     */
    private static Map<String, String> mergeFieldsFor(CampaignRecipient recipient, Subscriber subscriber) {
        Map<String, String> merge = new HashMap<>();
        merge.put("NAME", recipient.getName());
        merge.put("EMAIL", recipient.getEmail());
        merge.put("FIRST_NAME", firstWord(recipient.getName()));
        merge.put("LAST_NAME", subscriber == null ? "" : nz(subscriber.getLastName()));
        merge.put("PHONE", subscriber == null ? "" : nz(subscriber.getPhone()));
        merge.put("COMPANY", subscriber == null ? "" : nz(subscriber.getCompany()));
        return merge;
    }

    /** Titles that are never a name on their own, lower case, trailing dot stripped. */
    private static final java.util.Set<String> HONORIFICS = java.util.Set.of(
            "dr", "doctor", "prof", "professor", "mr", "mrs", "ms", "miss", "shri", "smt", "sri");

    /**
     * The name to greet someone by.
     *
     * Taking the literal first word breaks on the audience this is most often used
     * for: "Dr. Akanksha Chichra" greets as "Dr.", which is worse than no name at
     * all. A leading title is therefore kept and carried onto the given name, so
     * that row greets as "Dr. Akanksha" and a plain "Akanksha Chichra" still
     * greets as "Akanksha".
     */
    private static String firstWord(String s) {
        if (s == null || s.isBlank()) return "there";
        String[] parts = s.trim().split("\s+");
        String head = parts[0];
        String bare = head.endsWith(".") ? head.substring(0, head.length() - 1) : head;

        if (HONORIFICS.contains(bare.toLowerCase(java.util.Locale.ROOT))) {
            // A title with nothing after it tells us nothing, so fall back.
            return parts.length > 1 ? head + " " + parts[1] : "there";
        }
        return head;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }

    public static double rate(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
