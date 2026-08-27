package com.jarurat.mailer.directory;

import com.jarurat.mailer.directory.StalwartAdminService.DomainSummary;
import com.jarurat.mailer.directory.StalwartAdminService.MailboxSummary;
import com.jarurat.mailer.directory.StalwartAdminService.StalwartAdminException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.NoInitialContextException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * JSON behind the domain health screen: one card per domain, carrying what the
 * directory knows about it and what DNS says about it right now.
 *
 * Read only, and it answers even when nothing works. Two independent things feed a
 * card, the mail server for the counts and the public resolver for the records,
 * and either can be down without the other being affected. A screen that goes
 * blank because Stalwart is restarting would be at its least useful exactly when
 * somebody is looking at it to find out why mail stopped, so a failure on either
 * side becomes a null and a sentence rather than an error page.
 *
 * MAILBOX_MANAGE for the same reason the mailbox screen needs it: this names every
 * domain the server accepts mail for and how many people are on each, which is the
 * shape of the organisation's mail estate and not something every console user is
 * entitled to enumerate.
 */
@RestController
@RequestMapping("/api/admin/domains")
public class DomainApi {

    /** In the JDK since 1.4, and the reason there is no DNS library in the pom. */
    private static final String DNS_FACTORY = "com.sun.jndi.dns.DnsContextFactory";

    /**
     * Per lookup, with a single pass over the configured servers rather than the
     * provider's default four. That default is tuned for a directory lookup that has
     * to succeed; this is a status panel, and an operator would far rather read
     * "could not check" in three seconds than the truth in forty.
     */
    private static final String LOOKUP_TIMEOUT_MS = "3000";
    private static final String LOOKUP_RETRIES = "1";

    /**
     * The hard ceiling on the whole DNS phase, however many domains and lookups are
     * in flight. The per lookup timeout above is the JNDI provider's promise and
     * this one is ours: a black holed resolver, a name server that takes the packet
     * and never answers, a provider that ignores its own timeout, none of them can
     * hold the page longer than this.
     */
    private static final Duration BUDGET = Duration.ofSeconds(5);

    /**
     * Long enough that reopening the screen is instant and does not put another four
     * queries on the resolver, short enough that an operator who has just published
     * a record sees it on their next look rather than doubting the change. DNS TTLs
     * run to minutes and hours, so a minute of staleness is well inside what the
     * rest of the internet is already being served.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /**
     * JNDI parses the name it is handed as a CompositeName, where / and \ and quotes
     * are structure rather than text. Domain names arrive here from the mail server's
     * own directory so nothing exotic is expected, but a name that is not a hostname
     * is never handed to the resolver.
     *
     * The underscore is not decoration. Every name this class asks about apart from
     * the domain itself carries one - _dmarc.example.com, selector._domainkey.
     * example.com - and a pattern written from the hostname rules refuses all of
     * them. Costly to spot, because the refusal looks exactly like the record being
     * absent: an earlier version of this guard turned every DKIM lookup into a FAIL
     * without a single query leaving the box.
     */
    private static final Pattern RESOLVABLE =
            Pattern.compile("^[a-z0-9_]([a-z0-9._-]{0,251}[a-z0-9_])?$", Pattern.CASE_INSENSITIVE);

    private final StalwartAdminService directory;

    /**
     * SES DKIM tokens, empty by default and empty in practice.
     *
     * There is no way to derive these: SES generates three per identity and the only
     * places they exist are the SES console and the zone file. This app does talk to
     * SES elsewhere, but SesSender.identityHealth reports the DKIM status without
     * returning the tokens, so nothing in this process knows the names. The property
     * is here so a box that does know can have them checked; see dkim() for what
     * happens when it does not.
     */
    private final List<String> selectors;

    /**
     * The resolvers to ask, empty for whichever ones the operating system is
     * configured with. Empty is right on the server, where that is the VPC resolver.
     *
     * The escape hatch is here because a resolver that answers badly makes this
     * screen lie rather than fail, and the lie looks exactly like a broken domain.
     * Measured on a home connection while building this: asking the local resolver
     * for microsoft.com's TXT records returns 6 of the 61 that 8.8.8.8 returns, the
     * SPF record among the 55 missing, so the card reports "publishes no SPF record"
     * about a domain whose SPF is fine. Nothing in a DNS answer says it was
     * abridged, so the only fix available to whoever is looking at the screen is to
     * point it somewhere else.
     */
    private final String resolvers;

    /**
     * Virtual threads, application scoped, never shut down.
     *
     * The four questions about a domain are independent, so asking them in sequence
     * makes the screen wait for their sum instead of for the slowest one. A per
     * request executor in a try-with-resources would read more tidily and be wrong
     * here: close() waits for every task to finish, which is precisely the wait that
     * BUDGET exists to cut short. A virtual thread executor holds no pool and no
     * threads between requests, so keeping one for the life of the bean costs
     * nothing.
     */
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DomainApi(StalwartAdminService directory,
                     @Value("${jarurat.mail.dkim.selectors:}") String dkimSelectors,
                     @Value("${jarurat.mail.dns.servers:}") String dnsServers) {
        this.directory = directory;
        this.selectors = selectors(dkimSelectors);
        this.resolvers = resolvers(dnsServers);
    }

    // ------------------------------------------------------------------ read

    /**
     * One card per domain: the counts from the directory, the four records from DNS,
     * and a sentence when the directory half could not be read.
     *
     * A null count means not known, never zero. The difference matters on this
     * screen: "no mailboxes on this domain" is a fact worth acting on and "the mail
     * server did not answer" is not, and a 0 standing in for an unknown has sent
     * people looking for accounts that were never missing.
     */
    @GetMapping("")
    @PreAuthorize("hasAuthority('MAILBOX_MANAGE')")
    public Map<String, Object> list() {
        Directory known = readDirectory();
        List<String> names = names(known);
        Map<String, Map<String, Check>> dns = check(names);
        String home = directory.domain();

        List<Map<String, Object>> cards = new ArrayList<>();
        for (String name : names) {
            DomainSummary row = known.row(name);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("name", name);
            card.put("accountCount", known.countsKnown() ? accountCount(known.accounts(), name) : null);
            card.put("aliasCount", known.countsKnown() ? aliasCount(known.accounts(), name) : null);
            card.put("isDefault", name.equalsIgnoreCase(home));
            // Everything from here down is null when the directory row is missing,
            // which is the whole story on a box where Stalwart is unreachable: the
            // name came out of configuration and nothing else about it is known.
            card.put("domainAliases", row == null ? null : row.aliases());
            card.put("catchAllAddress", row == null ? null : blankToNull(row.catchAllAddress()));
            card.put("allowRelaying", row == null ? null : row.allowRelaying());
            card.put("createdAt", row == null ? null : blankToNull(row.createdAt()));
            card.put("dns", dns.get(name));
            cards.add(card);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domains", cards);
        // Mirrors /api/admin/mailboxes, which reports the same two facts, so the
        // console has one way of saying "the directory side is not available here".
        out.put("configured", directory.configured());
        out.put("directoryDetail", known.detail());
        return out;
    }

    // ------------------------------------------------------------------ directory

    /**
     * The directory half of a card, with every failure turned into a sentence.
     *
     * Catching RuntimeException this broadly is normally how a bug gets buried, and
     * it is deliberate here: this endpoint's contract is that it renders. The two
     * reads are caught separately because they fail independently. A Stalwart with
     * no x:Domain object still has accounts, and losing the domain list should not
     * also cost the counts on the fallback card.
     */
    private Directory readDirectory() {
        if (!directory.configured()) {
            return new Directory(List.of(), List.of(), false,
                    "Mailbox administration is not configured on this server, so the domain list and the "
                            + "account counts are unavailable. The DNS checks do not depend on it.");
        }

        List<DomainSummary> domains;
        try {
            domains = directory.domains();
        } catch (RuntimeException e) {
            return new Directory(List.of(), List.of(), false, directoryFailure(e));
        }
        try {
            return new Directory(domains, directory.list(), true, null);
        } catch (RuntimeException e) {
            return new Directory(domains, List.of(), false, directoryFailure(e));
        }
    }

    /**
     * StalwartAdminException messages are written for an operator to read and are
     * guaranteed by that class to carry no credential, so they come through whole.
     * Anything else is named by its type and nothing more: an arbitrary getMessage()
     * from this side of the app could be carrying a URL, a header or worse.
     */
    private static String directoryFailure(RuntimeException e) {
        String why = e instanceof StalwartAdminException && e.getMessage() != null
                ? e.getMessage()
                : e.getClass().getSimpleName() + ".";
        return "The mail server could not be read, so the account counts are unavailable: " + why;
    }

    /**
     * The domains to render, default first and the rest alphabetical.
     *
     * Falls back to the one configured domain when the directory gave nothing,
     * because a screen listing no domains at all reads as "this server handles no
     * mail", which is never what a failed read means.
     */
    private List<String> names(Directory known) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (DomainSummary domain : known.domains()) names.add(domain.name());
        String home = directory.domain();
        if (names.isEmpty() && !home.isBlank()) names.add(home);

        List<String> ordered = new ArrayList<>(names);
        ordered.sort(Comparator.comparingInt((String name) -> name.equalsIgnoreCase(home) ? 0 : 1)
                .thenComparing(Comparator.naturalOrder()));
        return ordered;
    }

    /**
     * Mailboxes on this domain.
     *
     * The role filter is not cosmetic. x:Account/get returns the domain row itself
     * alongside the user accounts, and StalwartAdminService fills in a missing
     * address by qualifying the name, so the domain row arrives looking like
     * jarurat.care@jarurat.care and would otherwise be counted as somebody's mailbox.
     */
    private static Integer accountCount(List<MailboxSummary> accounts, String domain) {
        int count = 0;
        for (MailboxSummary account : accounts) {
            if (isDomainRow(account)) continue;
            if (domain.equalsIgnoreCase(hostOf(account.emailAddress()))) count++;
        }
        return count;
    }

    /**
     * Mailbox aliases on this domain, counted from the accounts rather than taken
     * from x:Domain.aliases, which holds alternate names for the domain itself and
     * is a different fact entirely.
     *
     * One caveat on a multi domain server: the service qualifies an alias that
     * arrived without a full address using the single domain the console is
     * configured for, so aliases on a second domain can be attributed to the first.
     * Right on this box, which serves one domain, and worth revisiting the day it
     * serves two.
     */
    private static Integer aliasCount(List<MailboxSummary> accounts, String domain) {
        int count = 0;
        for (MailboxSummary account : accounts) {
            if (isDomainRow(account)) continue;
            for (String alias : account.aliases()) {
                if (domain.equalsIgnoreCase(hostOf(alias))) count++;
            }
        }
        return count;
    }

    private static boolean isDomainRow(MailboxSummary account) {
        return "Domain".equalsIgnoreCase(account.role());
    }

    private static String hostOf(String address) {
        int at = address == null ? -1 : address.lastIndexOf('@');
        return at < 0 ? "" : address.substring(at + 1).trim();
    }

    // ------------------------------------------------------------------ dns

    /**
     * The four checks for every domain, all in flight at once and all bounded by one
     * shared deadline.
     *
     * One deadline for the page rather than one per lookup: the queries are already
     * running side by side, so what an operator waits for is the slowest of them and
     * not their sum, however many domains the server has.
     */
    private Map<String, Map<String, Check>> check(List<String> names) {
        Map<String, Map<String, Check>> answers = new LinkedHashMap<>();
        Map<String, Map<String, Future<Check>>> pending = new LinkedHashMap<>();

        for (String name : names) {
            if (!RESOLVABLE.matcher(name).matches()) {
                answers.put(name, every(new Check(Status.UNKNOWN, List.of(),
                        "\"" + name + "\" is not a hostname, so it was never sent to the resolver.")));
                continue;
            }
            Map<String, Check> hit = cached(name);
            if (hit != null) {
                answers.put(name, hit);
                continue;
            }
            Map<String, Future<Check>> started = new LinkedHashMap<>();
            started.put("mx", lookups.submit(() -> mx(name)));
            started.put("spf", lookups.submit(() -> spf(name)));
            started.put("dmarc", lookups.submit(() -> dmarc(name)));
            started.put("dkim", lookups.submit(() -> dkim(name)));
            pending.put(name, started);
        }

        long deadline = System.nanoTime() + BUDGET.toNanos();
        for (Map.Entry<String, Map<String, Future<Check>>> domain : pending.entrySet()) {
            Map<String, Check> checks = new LinkedHashMap<>();
            boolean cacheable = true;
            for (Map.Entry<String, Future<Check>> started : domain.getValue().entrySet()) {
                Awaited awaited = await(started.getValue(), started.getKey(), deadline);
                checks.put(started.getKey(), awaited.check());
                cacheable &= awaited.cacheable();
            }
            answers.put(domain.getKey(), checks);
            // Only a set of real answers is worth keeping. An UNKNOWN that came out
            // of our own deadline is not an answer about the domain at all, and
            // caching it would pin that blank for a minute after the resolver came
            // back. Nothing mutates checks past this point, so the map can be shared.
            if (cacheable) cache.put(domain.getKey(), new Cached(Instant.now().plus(CACHE_TTL), checks));
        }
        return answers;
    }

    private static Awaited await(Future<Check> lookup, String label, long deadlineNanos) {
        String what = label.toUpperCase(Locale.ROOT);
        try {
            return new Awaited(lookup.get(Math.max(0, deadlineNanos - System.nanoTime()),
                    TimeUnit.NANOSECONDS), true);
        } catch (TimeoutException e) {
            // Cancelled rather than abandoned. The interrupt may not reach a socket
            // read inside the JNDI provider, but the lookup carries its own timeout
            // and an unwanted virtual thread winding down costs nothing.
            lookup.cancel(true);
            return new Awaited(new Check(Status.UNKNOWN, List.of(),
                    "The " + what + " lookup did not answer within this page's DNS budget of "
                            + BUDGET.toSeconds() + " seconds."), false);
        } catch (ExecutionException e) {
            // The checks below catch their own failures, so arriving here means a
            // fault rather than a result. Named by type only, and not cached.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return new Awaited(new Check(Status.UNKNOWN, List.of(),
                    "The " + what + " lookup failed unexpectedly (" + cause.getClass().getSimpleName()
                            + ")."), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lookup.cancel(true);
            return new Awaited(new Check(Status.UNKNOWN, List.of(),
                    "Interrupted while waiting for the " + what + " lookup."), false);
        }
    }

    private Map<String, Check> cached(String domain) {
        Cached hit = cache.get(domain);
        if (hit == null) return null;
        if (Instant.now().isAfter(hit.expiresAt())) {
            cache.remove(domain, hit);
            return null;
        }
        return hit.checks();
    }

    /**
     * No MX is the one unambiguous failure on this screen. It does not mean the
     * domain is configured oddly, it means nothing on the internet knows where to
     * deliver its mail.
     */
    private Check mx(String domain) {
        try {
            List<String> raw = records(domain, "MX");
            if (raw.isEmpty()) {
                return new Check(Status.FAIL, List.of(), domain + " publishes no MX record, so no other "
                        + "mail server knows where to deliver mail addressed to it.");
            }
            List<String> found = new ArrayList<>();
            int nulls = 0;
            for (String record : byPreference(raw)) {
                // A null MX keeps its trailing dot, because that dot is the whole
                // record: strip it and the card shows "0 " and says nothing.
                if (isNullMx(record)) {
                    nulls++;
                    found.add(record.trim());
                } else {
                    found.add(trimDot(record));
                }
            }
            if (nulls == found.size()) {
                // RFC 7505. The domain has not forgotten to publish an MX, it has
                // deliberately published the one that says "I accept no mail", which
                // is a fail on a screen about a domain that is supposed to receive.
                return new Check(Status.FAIL, found, domain + " publishes a null MX, which is the RFC 7505 "
                        + "way of telling every sender on the internet that this domain accepts no mail.");
            }
            if (nulls > 0) {
                return new Check(Status.WARN, found, domain + " publishes a null MX alongside real ones. "
                        + "A null MX has to be the only MX record, so senders are being given two "
                        + "contradictory answers and which one wins is up to them.");
            }
            return new Check(Status.OK, found, found.size() == 1
                    ? "One mail exchanger."
                    : found.size() + " mail exchangers, listed in preference order.");
        } catch (NameNotFoundException e) {
            return new Check(Status.FAIL, List.of(), domain + " does not exist in DNS at all.");
        } catch (NamingException e) {
            return unknown("MX", domain, e);
        }
    }

    private Check spf(String domain) {
        try {
            List<String> spf = startingWith(records(domain, "TXT"), "v=spf1");
            if (spf.isEmpty()) {
                return new Check(Status.FAIL, List.of(), domain + " publishes no SPF record, so every "
                        + "receiver has to decide on its own whether mail claiming to come from it is genuine.");
            }
            if (spf.size() > 1) {
                // Flagged even though each record on its own may be perfectly
                // correct, because SPF is not additive. RFC 7208 has a receiver that
                // finds more than one v=spf1 record evaluate the domain as permerror
                // and stop: the second record does not extend the first, it switches
                // SPF off for the whole domain. This is the failure that hides behind
                // a green tick, since every record still reads correctly on its own,
                // and the usual cause is somebody adding a sender by pasting in the
                // line their vendor gave them.
                return new Check(Status.WARN, spf, domain + " publishes " + spf.size() + " SPF records "
                        + "and a domain may publish one. Receivers treat several as a permanent error and "
                        + "stop evaluating SPF, so this is worse than publishing none: merge them into a "
                        + "single v=spf1 record.");
            }
            return new Check(Status.OK, spf, "One SPF record, which is the only number that works.");
        } catch (NameNotFoundException e) {
            return new Check(Status.FAIL, List.of(), domain + " does not exist in DNS at all.");
        } catch (NamingException e) {
            return unknown("SPF", domain, e);
        }
    }

    private Check dmarc(String domain) {
        String name = "_dmarc." + domain;
        try {
            List<String> dmarc = startingWith(records(name, "TXT"), "v=DMARC1");
            if (dmarc.isEmpty()) return noDmarc(name);
            if (dmarc.size() > 1) {
                return new Check(Status.WARN, dmarc, name + " holds " + dmarc.size() + " DMARC records. "
                        + "RFC 7489 tells receivers to treat a domain with several as having none, so this "
                        + "publishes no policy at all.");
            }
            String policy = tag(dmarc.get(0), "p");
            if (policy.isEmpty()) {
                return new Check(Status.WARN, dmarc,
                        "The DMARC record carries no p= tag, so it asks receivers for nothing.");
            }
            if (policy.equalsIgnoreCase("none")) {
                return new Check(Status.WARN, dmarc, "The policy is p=none: receivers report on mail that "
                        + "fails authentication and deliver it anyway, so the domain is monitored rather "
                        + "than protected. That is the right place to start and the wrong place to stop.");
            }
            return new Check(Status.OK, dmarc, "The policy is p=" + policy.toLowerCase(Locale.ROOT) + ".");
        } catch (NameNotFoundException e) {
            // NXDOMAIN on _dmarc is how a domain says it publishes no DMARC record.
            // It is an answer, not a lookup failure.
            return noDmarc(name);
        } catch (NamingException e) {
            return unknown("DMARC", name, e);
        }
    }

    private static Check noDmarc(String name) {
        return new Check(Status.FAIL, List.of(), "There is no DMARC record at " + name + ". Gmail and "
                + "Yahoo have required one from bulk senders since 2024, and without it a receiver has no "
                + "instruction for mail that fails authentication.");
    }

    /**
     * DKIM, and the one check here that is allowed to say it does not know.
     *
     * This domain signs through Amazon SES, which publishes the keys as CNAMEs at
     * token._domainkey.domain. The tokens are generated per identity and exist in
     * exactly two places, the SES console and the zone: nothing in this process can
     * derive them, and SesSender reports the DKIM status without returning them. So
     * there is no name to ask about unless somebody configures one.
     *
     * UNKNOWN rather than FAIL, and that is the decision worth defending. Producing
     * a FAIL would mean guessing selectors and reporting the guesses as missing,
     * which proves nothing: a domain signing perfectly well under tokens we did not
     * guess would show a red cross. A false failure on a healthy setup is worse than
     * a blank, because it sends somebody to fix DKIM that is not broken and teaches
     * everybody else to ignore this screen. Selectors that were configured are a
     * different matter: those are names we were told to expect, so their absence
     * below is a real finding.
     */
    private Check dkim(String domain) {
        if (selectors.isEmpty()) {
            return new Check(Status.UNKNOWN, List.of(), "DKIM for this domain is signed by Amazon SES, "
                    + "which publishes the keys as CNAME records at <token>._domainkey." + domain
                    + ". SES issues those tokens per identity and this app is not told them, so there is no "
                    + "name to look up rather than a record that is missing. Set jarurat.mail.dkim.selectors "
                    + "to the three tokens, comma separated, to have them checked here; until then the "
                    + "authoritative answer is the DKIM status SES itself reports on the Overview screen.");
        }

        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String trouble = null;
        for (String selector : selectors) {
            String name = selector + "._domainkey." + domain;
            if (!RESOLVABLE.matcher(name).matches()) {
                missing.add(selector);
                continue;
            }
            try {
                List<String> cname = records(name, "CNAME");
                if (!cname.isEmpty()) {
                    found.add(selector + "._domainkey -> " + trimDot(cname.get(0)));
                    continue;
                }
                // A domain that signs somewhere other than SES publishes the key
                // itself as a TXT at the same name, so a missing CNAME is worth one
                // more question before it is called an absence.
                if (!startingWith(records(name, "TXT"), "v=DKIM1").isEmpty()) {
                    found.add(selector + "._domainkey -> key published directly as TXT");
                    continue;
                }
                missing.add(selector);
            } catch (NameNotFoundException e) {
                missing.add(selector);
            } catch (NamingException e) {
                trouble = dnsReason(e);
            }
        }

        if (found.isEmpty() && trouble != null) {
            return new Check(Status.UNKNOWN, List.of(),
                    "The DKIM lookups for " + domain + " got no answer: " + trouble + ".");
        }
        if (found.isEmpty()) {
            return new Check(Status.FAIL, List.of(), "None of the " + selectors.size() + " configured DKIM "
                    + "selectors resolve under _domainkey." + domain + ", so nothing this domain sends is "
                    + "signed under them.");
        }
        if (missing.isEmpty() && trouble == null) {
            return new Check(Status.OK, found,
                    found.size() == 1 ? "One DKIM key published." : found.size() + " DKIM keys published.");
        }
        return new Check(Status.WARN, found, found.size() + " of " + selectors.size() + " configured DKIM "
                + "selectors resolve"
                + (missing.isEmpty() ? "" : ", missing " + String.join(", ", missing))
                + (trouble == null ? "." : ", and one lookup got no answer: " + trouble + "."));
    }

    // ------------------------------------------------------------------ resolver

    /**
     * One question to the resolver, with the context closed afterwards.
     *
     * A fresh DirContext per lookup because a DirContext is not thread safe and these
     * run side by side; the DNS provider opens nothing when it is created, so this is
     * close to free. With no provider URL the provider uses whichever resolvers the
     * operating system is configured with, which is the default on purpose: this
     * screen should report what this box sees, because that is what its mail flow
     * actually depends on.
     */
    private List<String> records(String name, String type) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, DNS_FACTORY);
        env.put("com.sun.jndi.dns.timeout.initial", LOOKUP_TIMEOUT_MS);
        env.put("com.sun.jndi.dns.timeout.retries", LOOKUP_RETRIES);
        if (!resolvers.isEmpty()) env.put(Context.PROVIDER_URL, resolvers);

        DirContext ctx = new InitialDirContext(env);
        try {
            Attributes attributes = ctx.getAttributes(name, new String[]{type});
            Attribute attribute = attributes.get(type);
            if (attribute == null) return List.of();
            List<String> values = new ArrayList<>(attribute.size());
            for (int i = 0; i < attribute.size(); i++) {
                Object value = attribute.get(i);
                if (value == null) continue;
                String cleaned = unquote(String.valueOf(value));
                if (!cleaned.isEmpty()) values.add(cleaned);
            }
            return values;
        } finally {
            try {
                ctx.close();
            } catch (NamingException e) {
                // Closing a DNS context releases nothing that matters, and a failure
                // here would replace the answer we came for with a shrug.
            }
        }
    }

    /**
     * A TXT record longer than 255 bytes travels as several character strings and
     * this provider hands them back joined with a space, so a long SPF or DKIM value
     * can come out carrying a space that is not in the zone. Harmless for the prefix
     * and tag tests here, and the reason a value shown on a card can differ by a
     * space from what was published.
     */
    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        return v.trim();
    }

    private static String trimDot(String value) {
        String v = value.trim();
        return v.endsWith(".") ? v.substring(0, v.length() - 1) : v;
    }

    private static List<String> startingWith(List<String> values, String prefix) {
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) out.add(value);
        }
        return out;
    }

    private static List<String> byPreference(List<String> raw) {
        List<String> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt(DomainApi::preference).thenComparing(Comparator.naturalOrder()));
        return sorted;
    }

    /** The root as an MX target, which is RFC 7505 for "this domain receives no mail". */
    private static boolean isNullMx(String record) {
        int space = record.indexOf(' ');
        String target = space < 0 ? "" : record.substring(space + 1).trim();
        return target.isEmpty() || target.equals(".");
    }

    /** An MX whose preference will not parse sorts last rather than vanishing from the card. */
    private static int preference(String record) {
        int space = record.indexOf(' ');
        try {
            return Integer.parseInt(space < 0 ? record.trim() : record.substring(0, space).trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** The value of one DMARC tag, or empty. Tags are semicolon separated and case insensitive. */
    private static String tag(String record, String name) {
        for (String part : record.split(";")) {
            String candidate = part.trim();
            if (candidate.regionMatches(true, 0, name + "=", 0, name.length() + 1)) {
                return candidate.substring(name.length() + 1).trim();
            }
        }
        return "";
    }

    private static Check unknown(String what, String name, NamingException e) {
        return new Check(Status.UNKNOWN, List.of(), "Could not look up " + what + " for " + name + ": "
                + dnsReason(e) + ". That is the lookup failing, not a verdict on the record.");
    }

    /**
     * A short phrase for each failure an operator can act on, and the exception type
     * for the rest. The provider's own message is not passed through: it is written
     * for a stack trace and can name the resolvers this box uses, which is not a
     * detail a status panel needs to publish.
     */
    private static String dnsReason(NamingException e) {
        if (e instanceof CommunicationException) return "the resolver did not answer in time";
        if (e instanceof ServiceUnavailableException) return "no DNS server was reachable";
        // Fires when the runtime was built without jdk.naming.dns. Named explicitly
        // because the alternative is an operator reading a class name and concluding
        // their DNS is broken when the JVM simply cannot ask.
        if (e instanceof NoInitialContextException) return "this JVM has no DNS provider available";
        return e.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Accepts the tokens on their own or a whole record name pasted out of the SES
     * console, since both name the same key and neither is a mistake.
     */
    private static List<String> selectors(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,\\s]+")) {
            String token = part.trim();
            int marker = token.toLowerCase(Locale.ROOT).indexOf("._domainkey");
            if (marker > 0) token = token.substring(0, marker);
            if (!token.isEmpty()) out.add(token);
        }
        return List.copyOf(out);
    }

    /**
     * Addresses or whole dns:// URLs, in the space separated form the JNDI provider
     * wants. It tries them in order and moves on when one does not answer.
     */
    private static String resolvers(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> urls = new ArrayList<>();
        for (String part : raw.split("[,\\s]+")) {
            String server = part.trim();
            if (server.isEmpty()) continue;
            urls.add(server.contains("://") ? server : "dns://" + server);
        }
        return String.join(" ", urls);
    }

    private static Map<String, Check> every(Check check) {
        Map<String, Check> checks = new LinkedHashMap<>();
        for (String name : List.of("mx", "spf", "dmarc", "dkim")) checks.put(name, check);
        return checks;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ------------------------------------------------------------------ shapes

    /** OK is a pass, WARN is published but wrong, FAIL is absent, UNKNOWN is nobody's fault. */
    public enum Status { OK, WARN, FAIL, UNKNOWN }

    /**
     * found carries the records exactly as they were published, so an operator can
     * compare a card against their zone without reaching for another tool. detail is
     * the sentence explaining the status and is present on every result, passes
     * included.
     */
    public record Check(Status status, List<String> found, String detail) {

        public Check {
            found = found == null ? List.of() : List.copyOf(found);
        }
    }

    private record Cached(Instant expiresAt, Map<String, Check> checks) { }

    /** cacheable is false for a result our own deadline produced, which is not an answer. */
    private record Awaited(Check check, boolean cacheable) { }

    /** countsKnown separates "no mailboxes" from "the mail server did not answer". */
    private record Directory(List<DomainSummary> domains,
                             List<MailboxSummary> accounts,
                             boolean countsKnown,
                             String detail) {

        DomainSummary row(String name) {
            for (DomainSummary domain : domains) {
                if (domain.name().equalsIgnoreCase(name)) return domain;
            }
            return null;
        }
    }
}
