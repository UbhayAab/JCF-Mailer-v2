package com.jarurat.mailer.verification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MX and address lookups over the JDK's own DNS provider, so no new dependency.
 *
 * The cache is the whole reason a 12,000 row list finishes in minutes instead of
 * hours: those rows share a few hundred domains, and gmail.com only has to be
 * asked once.
 */
@Component
public class DnsLookup {

    /** UNKNOWN is not a failure of the address, it is a failure of ours. */
    public enum Status { RESOLVED, NXDOMAIN, UNKNOWN }

    public record DomainInfo(Status status, List<String> mxHosts, boolean hasAddress, boolean nullMx) {

        public boolean resolves() { return status == Status.RESOLVED; }
        public boolean hasMx() { return !mxHosts.isEmpty(); }
        public String primaryMx() { return mxHosts.isEmpty() ? null : mxHosts.get(0); }
    }

    private static final String DNS_FACTORY = "com.sun.jndi.dns.DnsContextFactory";

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final long cacheMillis;
    private final String timeoutMillis;
    private final String retries;

    public DnsLookup(@Value("${verification.dnsCacheMinutes:120}") int cacheMinutes,
                     @Value("${verification.dnsTimeoutMs:2500}") int timeoutMs,
                     @Value("${verification.dnsRetries:1}") int retries) {
        this.cacheMillis = Math.max(1, cacheMinutes) * 60_000L;
        this.timeoutMillis = String.valueOf(Math.max(500, timeoutMs));
        this.retries = String.valueOf(Math.max(0, retries));
    }

    private record Cached(DomainInfo info, long at) {}

    public DomainInfo lookup(String domain) {
        String key = domain == null ? "" : domain.trim().toLowerCase();
        if (key.isEmpty()) return new DomainInfo(Status.NXDOMAIN, List.of(), false, false);

        Cached hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at() < cacheMillis) return hit.info();

        DomainInfo info = resolve(key);
        // A timeout or SERVFAIL must not be cached, otherwise one bad minute on
        // the resolver marks a whole list unverifiable for the next two hours.
        if (info.status() != Status.UNKNOWN) cache.put(key, new Cached(info, System.currentTimeMillis()));
        return info;
    }

    /**
     * One record type per query, never a combined request.
     *
     * Asking this provider for {"MX","A","AAAA"} in a single getAttributes call
     * makes it issue a DNS ANY query, and RFC 8482 resolvers answer those with a
     * minimal or empty response. Measured against eight live domains over three
     * rounds, the combined form returned nothing at all for nic.in and
     * jarurat.care every single time and for six of the eight by the third
     * round, while the single-type form was correct 24 times out of 24. The
     * combined form would have reported "No MX record" for most of the audience.
     *
     * The common case still costs exactly one query, because a domain that has
     * an MX never needs the address lookups.
     */
    private DomainInfo resolve(String domain) {
        DirContext ctx = null;
        try {
            ctx = context();

            List<String> mx = readMx(attribute(ctx, domain, "MX"));
            boolean nullMx = mx.size() == 1 && ".".equals(mx.get(0));
            if (nullMx) mx = List.of();
            if (!mx.isEmpty() || nullMx) return new DomainInfo(Status.RESOLVED, mx, false, nullMx);

            // No MX. RFC 5321 lets the address record stand in, so it decides
            // between "misconfigured but reachable" and "accepts no mail".
            boolean hasAddress = attribute(ctx, domain, "A") != null
                    || attribute(ctx, domain, "AAAA") != null;
            return new DomainInfo(Status.RESOLVED, List.of(), hasAddress, false);

        } catch (NameNotFoundException e) {
            return new DomainInfo(Status.NXDOMAIN, List.of(), false, false);
        } catch (Exception e) {
            return new DomainInfo(Status.UNKNOWN, List.of(), false, false);
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * A missing record type is an empty Attributes, not an exception, but a
     * missing name is NameNotFoundException and has to propagate so the caller
     * can tell "no MX" apart from "no such domain".
     */
    private static Attribute attribute(DirContext ctx, String domain, String type) throws Exception {
        Attributes attrs = ctx.getAttributes(domain, new String[]{type});
        return attrs == null ? null : attrs.get(type);
    }

    /**
     * A fresh context per lookup. InitialDirContext is not thread safe and this
     * runs on hundreds of virtual threads at once, so sharing one would be a
     * data race. The per-domain cache above keeps the count down to the number
     * of distinct domains, not the number of addresses.
     */
    private DirContext context() throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, DNS_FACTORY);
        env.put(Context.PROVIDER_URL, "dns:");
        env.put("com.sun.jndi.dns.timeout.initial", timeoutMillis);
        env.put("com.sun.jndi.dns.timeout.retries", retries);
        return new InitialDirContext(env);
    }

    /** MX values arrive as "10 mail.example.com." so sort by preference and tidy. */
    private static List<String> readMx(Attribute attribute) {
        if (attribute == null) return List.of();

        record Entry(int preference, String host) {}
        List<Entry> entries = new ArrayList<>();
        try {
            NamingEnumeration<?> values = attribute.getAll();
            while (values.hasMore()) {
                String raw = String.valueOf(values.next()).trim();
                int space = raw.indexOf(' ');
                if (space < 0) continue;
                int preference;
                try {
                    preference = Integer.parseInt(raw.substring(0, space).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String host = raw.substring(space + 1).trim();
                if (host.endsWith(".") && host.length() > 1) host = host.substring(0, host.length() - 1);
                if (!host.isEmpty()) entries.add(new Entry(preference, host));
            }
        } catch (Exception e) {
            return List.of();
        }

        entries.sort(Comparator.comparingInt(Entry::preference));
        List<String> hosts = new ArrayList<>(entries.size());
        for (Entry e : entries) hosts.add(e.host());
        return Collections.unmodifiableList(hosts);
    }

    public void clearCache() { cache.clear(); }

    public int cacheSize() { return cache.size(); }
}
