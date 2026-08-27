package com.jarurat.mailer.verification;

import com.jarurat.mailer.models.MailingList;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.ListMemberRepository;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.repositories.SubscriberRepository;
import com.jarurat.mailer.services.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs verification over a whole list and remembers the answers.
 *
 * Two properties make a 12,000 address list workable. Concurrency is virtual
 * threads behind a semaphore, so thousands of DNS waits cost almost nothing but
 * the resolver never sees more than the configured number of questions at once.
 * Resumability is the results table: every address is written the moment it is
 * judged, and a restarted run skips anything already answered inside the
 * freshness window. Kill the process halfway through and the second run only
 * pays for what is left.
 */
@Service
public class VerificationService {

    private static final int PAGE = 500;
    /** Postgres tolerates far more, but chunking keeps the IN list predictable. */
    private static final int IN_CHUNK = 900;

    private final EmailVerifier verifier;
    private final SmtpProbe smtp;
    private final DisposableDomains disposableDomains;
    private final VerificationResultRepository results;
    private final SubscriberRepository subscribers;
    private final MailingListRepository lists;
    private final ListMemberRepository members;
    private final AuditService audit;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore gate;
    private final Map<String, Run> liveRuns = new ConcurrentHashMap<>();

    private final int freshDays;
    private final boolean allowRisky;
    private final boolean blockUnverified;
    private final int maxAdhoc;
    private final int retentionDays;

    public VerificationService(EmailVerifier verifier,
                               SmtpProbe smtp,
                               DisposableDomains disposableDomains,
                               VerificationResultRepository results,
                               SubscriberRepository subscribers,
                               MailingListRepository lists,
                               ListMemberRepository members,
                               AuditService audit,
                               // Measured, not guessed: 40 cold distinct domains took 357ms
                               // sequentially, 24ms at 16 in flight, but 40ms at 64. Pushing more
                               // questions at the resolver at once makes it slower, not faster.
                               // 16 also sits under the 20 connection Hikari pool, so the per
                               // address save cannot starve the console that is watching progress.
                               @Value("${verification.concurrency:16}") int concurrency,
                               @Value("${verification.freshDays:90}") int freshDays,
                               @Value("${verification.allowRisky:true}") boolean allowRisky,
                               @Value("${verification.blockUnverified:false}") boolean blockUnverified,
                               @Value("${verification.maxAdhoc:500}") int maxAdhoc,
                               @Value("${verification.retentionDays:365}") int retentionDays) {
        this.verifier = verifier;
        this.smtp = smtp;
        this.disposableDomains = disposableDomains;
        this.results = results;
        this.subscribers = subscribers;
        this.lists = lists;
        this.members = members;
        this.audit = audit;
        this.gate = new Semaphore(Math.max(1, concurrency));
        this.freshDays = Math.max(1, freshDays);
        this.allowRisky = allowRisky;
        this.blockUnverified = blockUnverified;
        this.maxAdhoc = Math.max(1, maxAdhoc);
        this.retentionDays = Math.max(freshDays, retentionDays);
    }

    // ------------------------------------------------------------------
    // Live run bookkeeping
    // ------------------------------------------------------------------

    /** In-memory only. The durable state is the results table, not this. */
    public static class Run {
        public final String key;
        public final String label;
        public volatile String status = "RUNNING"; // RUNNING | DONE | CANCELLED | FAILED
        public volatile int total;
        public final AtomicInteger checked = new AtomicInteger();
        public final AtomicInteger skipped = new AtomicInteger();
        public final AtomicInteger deliverable = new AtomicInteger();
        public final AtomicInteger risky = new AtomicInteger();
        public final AtomicInteger undeliverable = new AtomicInteger();
        public final AtomicInteger unresolved = new AtomicInteger();
        public final AtomicBoolean cancelled = new AtomicBoolean();
        public volatile long startedAt = System.currentTimeMillis();
        public volatile long finishedAt;
        public volatile String lastError;

        Run(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void record(EmailCheck check) {
            checked.incrementAndGet();
            if (check.transientFailure()) { unresolved.incrementAndGet(); return; }
            switch (check.verdict()) {
                case DELIVERABLE -> deliverable.incrementAndGet();
                case RISKY -> risky.incrementAndGet();
                case UNDELIVERABLE -> undeliverable.incrementAndGet();
            }
        }
    }

    public Run getRun(String key) { return liveRuns.get(key); }

    public Map<String, Object> progress(String key) {
        Run run = liveRuns.get(key);
        if (run == null) return Map.of("running", false);

        int checked = run.checked.get();
        int skipped = run.skipped.get();
        // A resumed run skips most of the list, so checked alone never reaches
        // total and a bar driven off it would stall short of the end forever.
        int done = checked + skipped;
        long until = run.finishedAt > 0 ? run.finishedAt : System.currentTimeMillis();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", "RUNNING".equals(run.status));
        m.put("key", run.key);
        m.put("label", run.label);
        m.put("status", run.status);
        m.put("total", run.total);
        m.put("done", done);
        m.put("percent", run.total <= 0 ? 100.0 : percent(Math.min(done, run.total), run.total));
        m.put("checked", checked);
        m.put("skipped", skipped);
        m.put("deliverable", run.deliverable.get());
        m.put("risky", run.risky.get());
        m.put("undeliverable", run.undeliverable.get());
        m.put("unresolved", run.unresolved.get());
        m.put("elapsedSeconds", (until - run.startedAt) / 1000);
        m.put("lastError", run.lastError == null ? "" : run.lastError);
        return m;
    }

    public boolean cancel(String key) {
        Run run = liveRuns.get(key);
        if (run == null || !"RUNNING".equals(run.status)) return false;
        run.cancelled.set(true);
        return true;
    }

    // ------------------------------------------------------------------
    // Verifying a whole list
    // ------------------------------------------------------------------

    public Run startListRun(Long listId, boolean force) {
        MailingList list = lists.findById(listId)
                .orElseThrow(() -> new IllegalArgumentException("No such list."));

        String key = "list:" + listId;
        Run existing = liveRuns.get(key);
        if (existing != null && "RUNNING".equals(existing.status))
            throw new IllegalStateException("This list is already being verified.");

        Run run = new Run(key, list.getName());
        run.total = (int) members.countByListId(listId);
        liveRuns.put(key, run);

        // Captured here because the worker thread has no security context.
        String actor = AuditService.currentActor();
        audit.record("VERIFICATION_STARTED", list.getName(),
                run.total + " addresses" + (force ? ", forced re-check" : ""));

        workers.submit(() -> runList(listId, force, run, actor));
        return run;
    }

    private void runList(Long listId, boolean force, Run run, String actor) {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(freshDays);
            int page = 0;

            while (!run.cancelled.get()) {
                // Sorted by id so offset paging cannot repeat or skip a row.
                Page<Subscriber> chunk = subscribers.search("", "", listId,
                        PageRequest.of(page, PAGE, Sort.by("id")));
                if (chunk.isEmpty()) break;

                List<String> emails = chunk.getContent().stream()
                        .map(Subscriber::getEmail)
                        .filter(e -> e != null && !e.isBlank())
                        .map(EmailVerifier::normalise)
                        .distinct()
                        .toList();

                if (!emails.isEmpty()) {
                    List<String> todo = emails;
                    if (!force) {
                        Set<String> fresh = new HashSet<>(results.findFreshEmails(emails, since));
                        if (!fresh.isEmpty()) {
                            run.skipped.addAndGet(fresh.size());
                            todo = emails.stream().filter(e -> !fresh.contains(e)).toList();
                        }
                    }

                    List<Future<?>> inFlight = new ArrayList<>(todo.size());
                    for (String email : todo) {
                        if (run.cancelled.get()) break;
                        inFlight.add(workers.submit(() -> verifyOne(email, actor, run)));
                    }
                    for (Future<?> f : inFlight) {
                        try { f.get(); } catch (Exception ignored) {}
                    }
                }

                if (!chunk.hasNext()) break;
                page++;
            }

            run.status = run.cancelled.get() ? "CANCELLED" : "DONE";
            run.finishedAt = System.currentTimeMillis();
            System.out.println("Verification " + run.key + " " + run.status
                    + ". checked=" + run.checked.get() + " skipped=" + run.skipped.get()
                    + " deliverable=" + run.deliverable.get() + " risky=" + run.risky.get()
                    + " undeliverable=" + run.undeliverable.get()
                    + " unresolved=" + run.unresolved.get());
        } catch (Exception e) {
            run.status = "FAILED";
            run.lastError = e.getMessage();
            run.finishedAt = System.currentTimeMillis();
            System.err.println("Verification run " + run.key + " aborted: " + e);
        }
    }

    private void verifyOne(String email, String actor, Run run) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            EmailCheck check = verifier.check(email);
            // A DNS timeout is our problem, not the address's. Persisting it
            // would freeze that lie in place for the whole freshness window.
            if (!check.transientFailure()) store(check, actor);
            run.record(check);
        } catch (Exception e) {
            // Still counted, otherwise the progress total never closes and the
            // run looks stuck at 99% when a handful of addresses threw.
            run.checked.incrementAndGet();
            run.unresolved.incrementAndGet();
            run.lastError = e.getMessage();
        } finally {
            gate.release();
        }
    }

    private void store(EmailCheck check, String actor) {
        try {
            // save() on an entity with its natural key already set is a merge,
            // so a re-check overwrites rather than duplicating.
            results.save(new VerificationResult(check, actor));
        } catch (Exception e) {
            // Two runs over overlapping lists can hit the same address at once.
            System.err.println("Could not store verification for " + check.email() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Ad hoc addresses
    // ------------------------------------------------------------------

    /** Synchronous, capped, for the paste-a-few-addresses box. */
    public List<EmailCheck> verifyAddresses(Collection<String> raw, boolean force) {
        Set<String> wanted = normalised(raw);
        if (wanted.isEmpty()) return List.of();
        if (wanted.size() > maxAdhoc)
            throw new IllegalArgumentException("Verify at most " + maxAdhoc
                    + " addresses at a time here. Use a list for anything larger.");

        LocalDateTime since = LocalDateTime.now().minusDays(freshDays);
        String actor = AuditService.currentActor();

        Map<String, EmailCheck> cached = new LinkedHashMap<>();
        if (!force) {
            for (VerificationResult row : results.findAllById(wanted)) {
                if (row.getCheckedAt() != null && row.getCheckedAt().isAfter(since))
                    cached.put(row.getEmail(), row.toCheck());
            }
        }

        Map<String, Future<EmailCheck>> pending = new LinkedHashMap<>();
        for (String email : wanted) {
            if (cached.containsKey(email)) continue;
            pending.put(email, workers.submit(() -> {
                gate.acquire();
                try {
                    EmailCheck check = verifier.check(email);
                    if (!check.transientFailure()) store(check, actor);
                    return check;
                } finally {
                    gate.release();
                }
            }));
        }

        List<EmailCheck> out = new ArrayList<>(wanted.size());
        for (String email : wanted) {
            EmailCheck hit = cached.get(email);
            if (hit != null) { out.add(hit); continue; }
            try {
                out.add(pending.get(email).get());
            } catch (Exception e) {
                out.add(new EmailCheck(email, Verdict.RISKY, "Check failed: " + e.getMessage(),
                        null, null, null, null, false, false, false, true, null));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The gate the campaign dispatcher calls
    // ------------------------------------------------------------------

    /**
     * The addresses out of this batch that are safe to send to.
     *
     * Default policy is fail open: an address nobody has verified yet is not
     * evidence of a bad address, and defaulting to block would silently empty
     * every campaign the day this feature is switched on. Set
     * verification.blockUnverified=true once a list has actually been run to
     * flip that to fail closed.
     */
    public Set<String> safeToSend(Collection<String> emails) {
        Set<String> wanted = normalised(emails);
        if (wanted.isEmpty()) return Set.of();
        wanted.removeAll(blockedWithReason(wanted).keySet());
        return wanted;
    }

    public boolean isSafeToSend(String email) {
        return !safeToSend(List.of(EmailVerifier.normalise(email))).isEmpty();
    }

    /** Same decision as safeToSend but keeps the reason, for the failure log. */
    public Map<String, String> blockedWithReason(Collection<String> emails) {
        Set<String> wanted = normalised(emails);
        if (wanted.isEmpty()) return Map.of();

        LocalDateTime since = LocalDateTime.now().minusDays(freshDays);
        Set<String> acceptable = allowRisky
                ? Set.of(Verdict.DELIVERABLE.name(), Verdict.RISKY.name())
                : Set.of(Verdict.DELIVERABLE.name());

        Map<String, String> blocked = new LinkedHashMap<>();
        Set<String> answered = new HashSet<>();

        for (List<String> chunk : chunks(new ArrayList<>(wanted))) {
            for (VerificationResult row : results.findAllById(chunk)) {
                if (row.getCheckedAt() == null || !row.getCheckedAt().isAfter(since)) continue;
                if (acceptable.contains(row.getVerdict())) {
                    answered.add(row.getEmail());
                } else {
                    blocked.put(row.getEmail(),
                            row.getReason() == null || row.getReason().isBlank()
                                    ? row.getVerdict() : row.getReason());
                }
            }
        }

        if (blockUnverified) {
            for (String email : wanted) {
                if (!answered.contains(email) && !blocked.containsKey(email))
                    blocked.put(email, "Not verified");
            }
        }
        return blocked;
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /**
     * The same numbers as summary() but typed, for callers that have to reason
     * about them rather than render them. The pre-send bounce estimate leans on
     * this, and it needs checkedFraction to decide whether it has enough of an
     * answer to be allowed to speak at all.
     */
    public record Coverage(long audience, long checked, long unchecked,
                           long deliverable, long risky, long undeliverable) {

        /** How much of the audience holds a usable answer, 0 to 1. */
        public double checkedFraction() {
            return audience == 0 ? 0 : (double) checked / audience;
        }

        /** Share of the checked addresses that would hard bounce, 0 to 100. */
        public double undeliverablePercent() {
            return checked == 0 ? 0 : undeliverable * 100.0 / checked;
        }
    }

    public Coverage coverage(Long listId) {
        LocalDateTime since = LocalDateTime.now().minusDays(freshDays);
        List<VerificationResultRepository.VerdictCount> rows = listId == null
                ? results.countByVerdictForEveryone(since)
                : results.countByVerdictForList(listId, since);

        long deliverable = 0, risky = 0, undeliverable = 0, unchecked = 0;
        for (VerificationResultRepository.VerdictCount row : rows) {
            switch (String.valueOf(row.getVerdict())) {
                case "DELIVERABLE" -> deliverable = row.getTotal();
                case "RISKY" -> risky = row.getTotal();
                case "UNDELIVERABLE" -> undeliverable = row.getTotal();
                default -> unchecked = row.getTotal();
            }
        }

        long checked = deliverable + risky + undeliverable;
        return new Coverage(checked + unchecked, checked, unchecked, deliverable, risky, undeliverable);
    }

    public Map<String, Object> summary(Long listId) {
        Coverage c = coverage(listId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listId", listId);
        m.put("audience", c.audience());
        m.put("checked", c.checked());
        m.put("unchecked", c.unchecked());
        m.put("deliverable", c.deliverable());
        m.put("risky", c.risky());
        m.put("undeliverable", c.undeliverable());
        m.put("deliverablePercent", percent(c.deliverable(), c.checked()));
        m.put("riskyPercent", percent(c.risky(), c.checked()));
        m.put("undeliverablePercent", percent(c.undeliverable(), c.checked()));
        m.put("freshDays", freshDays);
        return m;
    }

    /** So the console can say out loud which checks are actually switched on. */
    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("freshDays", freshDays);
        m.put("allowRisky", allowRisky);
        m.put("blockUnverified", blockUnverified);
        m.put("maxAdhoc", maxAdhoc);
        m.put("retentionDays", retentionDays);
        m.put("smtpProbeEnabled", smtp.isEnabled());
        m.put("disposableDomains", disposableDomains.size());
        return m;
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    /** Expired rows are dead weight: the read path already refuses to trust them. */
    @Scheduled(initialDelay = 21_600_000L, fixedDelay = 86_400_000L)
    public void purgeExpired() {
        try {
            int removed = results.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
            if (removed > 0) System.out.println("Purged " + removed + " expired verification results.");
        } catch (Exception e) {
            System.err.println("Verification purge failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private static Set<String> normalised(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            String clean = EmailVerifier.normalise(s);
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    private static List<List<String>> chunks(List<String> all) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + IN_CHUNK)));
        }
        return out;
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
