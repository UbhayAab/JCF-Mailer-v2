package com.jarurat.mailer.verification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Opt-in SMTP RCPT TO probe. OFF BY DEFAULT, and it should stay that way.
 *
 * Why off:
 *  - Opening a connection, naming a sender and then dropping it without sending
 *    is exactly the fingerprint of a dictionary harvester. Microsoft, Google and
 *    most Barracuda appliances rate limit or blocklist the source IP for it, and
 *    the IP being blocklisted here is the one that also sends our real mail.
 *  - The answer is often a lie anyway. Large providers accept every RCPT at the
 *    edge and decide later, so a 250 proves nothing and a catch-all domain
 *    returns 250 for an address that does not exist.
 *  - On this infrastructure it cannot work regardless: the EC2 instances have
 *    outbound port 25 blocked by AWS, which is the same block that made the
 *    Stalwart queue silently time out for 135 seconds per message until
 *    everything was pointed at the SES relay on 587. A probe from this box will
 *    time out on connect every single time.
 *
 * Turn it on only from a machine with clean, disposable outbound IP reputation
 * and an unblocked port 25, via verification.smtp.enabled=true.
 */
@Component
public class SmtpProbe {

    /**
     * mailboxOk is tri-state on purpose: TRUE means the server accepted the
     * recipient, FALSE means it rejected it with a 5xx, null means we could not
     * get an answer and the caller must not pretend otherwise.
     */
    public record Outcome(Boolean mailboxOk, boolean catchAll, boolean transientFailure, String lastResponse) {

        static Outcome unknown(String why) { return new Outcome(null, false, true, why); }
    }

    private final boolean enabled;
    private final String heloDomain;
    private final String mailFrom;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /** One conversation per domain at a time. Parallel probes to one MX is abuse. */
    private final Map<String, Semaphore> perDomainGate = new ConcurrentHashMap<>();

    public SmtpProbe(@Value("${verification.smtp.enabled:false}") boolean enabled,
                     @Value("${verification.smtp.heloDomain:${aws.ses.domain:jarurat.care}}") String heloDomain,
                     @Value("${verification.smtp.mailFrom:${aws.ses.fromEmail:admin@jarurat.care}}") String mailFrom,
                     @Value("${verification.smtp.connectTimeoutMs:5000}") int connectTimeoutMs,
                     @Value("${verification.smtp.readTimeoutMs:8000}") int readTimeoutMs) {
        this.enabled = enabled;
        this.heloDomain = heloDomain;
        this.mailFrom = mailFrom;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        if (enabled) {
            System.out.println("Verification SMTP probe is ENABLED. This connects to third party "
                    + "mail servers on port 25 and risks the sending IP's reputation.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Asks the domain's primary MX about one address, plus a random local part
     * first so a catch-all can be told apart from a real acceptance.
     */
    public Outcome probe(String domain, String email, String mxHost) {
        if (!enabled) return new Outcome(null, false, false, "probe disabled");
        if (mxHost == null || mxHost.isBlank()) return Outcome.unknown("no MX host");

        Semaphore gate = perDomainGate.computeIfAbsent(domain, d -> new Semaphore(1));
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.unknown("interrupted");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(mxHost, 25), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            try (BufferedReader in = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

                String greeting = read(in);
                if (code(greeting) != 220) return Outcome.unknown(greeting);

                String ehlo = say(in, out, "EHLO " + heloDomain);
                if (code(ehlo) != 250) {
                    ehlo = say(in, out, "HELO " + heloDomain);
                    if (code(ehlo) != 250) return Outcome.unknown(ehlo);
                }

                String from = say(in, out, "MAIL FROM:<" + mailFrom + ">");
                if (code(from) != 250) return Outcome.unknown(from);

                // Nobody can have this address, so a 250 means the domain says yes
                // to everything and its answer about the real address is worthless.
                String bait = "jcf-probe-" + UUID.randomUUID().toString().substring(0, 12) + "@" + domain;
                String baitReply = say(in, out, "RCPT TO:<" + bait + ">");
                boolean catchAll = code(baitReply) == 250 || code(baitReply) == 251;

                String realReply = say(in, out, "RCPT TO:<" + email + ">");
                int realCode = code(realReply);

                try { say(in, out, "QUIT"); } catch (Exception ignored) {}

                if (catchAll) return new Outcome(null, true, false, realReply);
                if (realCode == 250 || realCode == 251) return new Outcome(true, false, false, realReply);
                if (realCode >= 500 && realCode < 600) return new Outcome(false, false, false, realReply);
                // 4xx is greylisting or a rate limit, which says nothing about the address
                return Outcome.unknown(realReply);
            }
        } catch (Exception e) {
            return Outcome.unknown(String.valueOf(e.getMessage()));
        } finally {
            gate.release();
        }
    }

    private static String say(BufferedReader in, BufferedWriter out, String command) throws Exception {
        out.write(command);
        out.write("\r\n");
        out.flush();
        return read(in);
    }

    /** SMTP replies can be multiline: "250-STARTTLS" then "250 OK" ends it. */
    private static String read(BufferedReader in) throws Exception {
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (all.length() > 0) all.append('\n');
            all.append(line);
            if (line.length() < 4 || line.charAt(3) == ' ') break;
        }
        return all.toString();
    }

    private static int code(String reply) {
        if (reply == null || reply.isBlank()) return -1;
        String[] lines = reply.split("\n");
        String last = lines[lines.length - 1].trim();
        if (last.length() < 3) return -1;
        try {
            return Integer.parseInt(last.substring(0, 3));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
