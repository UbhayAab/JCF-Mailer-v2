package com.jarurat.mailer.messagelog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the receiving server's own answer out of Stalwart's delivery log.
 *
 * The message log needs the real SMTP reply, not "SES accepted the message".
 * The obvious source, SES event publishing, needs an SNS topic, and the IAM user
 * this account runs as is denied SNS:CreateTopic, so no genuine SES notification
 * can ever arrive. What does exist is Stalwart's own log on this same box, which
 * records every delivery attempt it makes with the far end's verbatim reply:
 *
 *   2026-08-16T06:41:06Z INFO Message delivered (delivery.delivered) queueId = 32418...,
 *     from = "priyanka@jarurat.care", to = ["x@gmail.com"], hostname = "email-smtp...",
 *     to = "x@gmail.com", code = 250, details = "Ok 010901a0094d760c-...", elapsed = 156ms
 *
 * Reading a local file the mail server wrote is also why this is safe where the
 * old SNS webhook was not: there is no request, so there is nothing to forge.
 *
 * Only mail that leaves through Stalwart appears here, which is webmail and
 * anything submitted over SMTP or IMAP. Campaign and transactional sends call the
 * SES API directly and never touch this log, so their rows stay SENT.
 */
@Component
public class StalwartDeliveryLog {

    /** Stalwart colours its log unless the tracer says otherwise, and rotation can bring it back. */
    private static final Pattern ANSI = Pattern.compile("\\e\\[[0-9;]*m");

    private static final Pattern FROM = Pattern.compile("\\bfrom = \"([^\"]*)\"");
    private static final Pattern TO_ONE = Pattern.compile("\\bto = \"([^\"]*)\"");
    private static final Pattern TO_MANY = Pattern.compile("\\bto = \\[([^\\]]*)\\]");
    private static final Pattern HOST = Pattern.compile("\\bhostname = \"([^\"]*)\"");
    private static final Pattern CODE = Pattern.compile("\\bcode = (\\d+)");
    private static final Pattern DETAILS = Pattern.compile("\\bdetails = \"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private static final Pattern QUEUE = Pattern.compile("\\bqueueId = (\\d+)");

    /*
     * Remote and local delivery do not report the same way. A relayed message logs
     * delivery.delivered with the far end's reply; a message dropped into a mailbox
     * on this box only logs delivery.dsn-success, hostname "localhost". Both are the
     * receiving server answering, so both count, and a remote delivery that emits
     * both is collapsed by the queue key below.
     */
    private static final List<String> GOOD = List.of("(delivery.delivered)", "(delivery.dsn-success)");
    private static final List<String> BAD = List.of("(delivery.message-rejected)", "(delivery.dsn-perm-fail)");

    /** One poll never reads more than this, so a log flood cannot take the heap with it. */
    private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;

    /** Enough to cover a day of sending; older keys cannot collide with a live row anyway. */
    private static final int SEEN_LIMIT = 20_000;

    private final MessageLogService messageLog;
    private final Path directory;
    private final String prefix;
    private final boolean enabled;

    /**
     * Events older than the process are replayed once on boot so a restart during
     * a send does not lose the reply, but only to upgrade rows that are already
     * there. Inventing rows for them would duplicate on every restart.
     */
    private final LocalDateTime startedAt = LocalDateTime.now();

    private String openFile;
    private long offset;
    private String partialLine = "";

    /**
     * Queue entry plus recipient, so the two events a single remote delivery emits
     * are one outcome and a boot replay cannot apply the same reply twice.
     */
    private final Map<String, Boolean> seen = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    };

    public StalwartDeliveryLog(MessageLogService messageLog,
                               @Value("${app.messagelog.stalwartLogDir:/var/log/stalwart}") String directory,
                               @Value("${app.messagelog.stalwartLogPrefix:stalwart}") String prefix,
                               @Value("${app.messagelog.stalwartLogEnabled:true}") boolean enabled) {
        this.messageLog = messageLog;
        this.directory = Path.of(directory);
        this.prefix = prefix;
        this.enabled = enabled;
    }

    /**
     * 20 seconds, because a delivery reply is worth having on the screen while the
     * person who sent the message is still looking at it, and reading the tail of
     * one file costs nothing.
     */
    @Scheduled(initialDelay = 15_000, fixedDelay = 20_000)
    public void poll() {
        if (!enabled) return;
        try {
            Path file = directory.resolve(prefix + "."
                    + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
            if (!Files.isReadable(file)) return;

            // A new day means a new file, and rotation or truncation means the
            // offset we were holding no longer points where we think it does.
            if (!file.toString().equals(openFile)) {
                openFile = file.toString();
                offset = 0;
                partialLine = "";
            }
            long size = Files.size(file);
            if (size < offset) {
                offset = 0;
                partialLine = "";
            }
            if (size == offset) return;

            long readTo = Math.min(size, offset + MAX_CHUNK_BYTES);
            byte[] buffer = new byte[(int) (readTo - offset)];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(offset);
                raf.readFully(buffer);
            }
            offset = readTo;

            // The chunk almost always ends mid line, so the remainder is carried
            // into the next poll rather than parsed as a truncated event.
            String text = partialLine + new String(buffer, StandardCharsets.UTF_8);
            int lastBreak = text.lastIndexOf('\n');
            if (lastBreak < 0) {
                partialLine = text;
                return;
            }
            partialLine = text.substring(lastBreak + 1);
            for (String line : text.substring(0, lastBreak).split("\n")) {
                consume(line);
            }
        } catch (Exception e) {
            System.err.println("Stalwart delivery log not read: " + e.getMessage());
        }
    }

    private void consume(String rawLine) {
        boolean delivered = matchesAny(rawLine, GOOD);
        if (!delivered && !matchesAny(rawLine, BAD)) return;

        String line = ANSI.matcher(rawLine).replaceAll("");
        LocalDateTime at = timestampOf(line);
        if (at == null) return;

        String from = group(FROM, line, "");
        String host = group(HOST, line, "");
        String detail = unescape(group(DETAILS, line, ""));
        // A DSN failure line states the code inside its detail text rather than in
        // its own field, so 0 means "the reply carried no separate code".
        int code = Integer.parseInt(group(CODE, line, "0"));
        String queue = group(QUEUE, line, "");

        for (String recipient : recipients(line)) {
            if (seen.put(queue + "|" + recipient, Boolean.TRUE) != null) continue;
            MessageLogService.DeliveryReport report =
                    new MessageLogService.DeliveryReport(from, recipient, host, code, detail, at, delivered);
            if (!messageLog.applyDeliveryReport(report) && at.isAfter(startedAt)) {
                messageLog.recordObservedDelivery(report);
            }
        }
    }

    private static boolean matchesAny(String line, List<String> markers) {
        for (String marker : markers) if (line.contains(marker)) return true;
        return false;
    }

    /**
     * A delivered line names the one recipient it delivered to after the envelope
     * list, so the last scalar wins. A rejection has no scalar because the whole
     * transaction failed, and then every address in the envelope is affected.
     */
    private static List<String> recipients(String line) {
        List<String> found = new ArrayList<>();
        Matcher scalar = TO_ONE.matcher(line);
        String last = null;
        while (scalar.find()) last = scalar.group(1);
        if (last != null && !last.isBlank()) {
            found.add(last.trim().toLowerCase());
            return found;
        }
        Matcher many = TO_MANY.matcher(line);
        if (many.find()) {
            Matcher each = QUOTED.matcher(many.group(1));
            while (each.find()) {
                String address = each.group(1).trim().toLowerCase();
                if (!address.isEmpty() && !found.contains(address)) found.add(address);
            }
        }
        return found;
    }

    /** Lines open with an RFC 3339 instant in UTC; the log rows are local time. */
    private static LocalDateTime timestampOf(String line) {
        int space = line.indexOf(' ');
        if (space <= 0) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(line.substring(0, space)), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private static String group(Pattern pattern, String line, String fallback) {
        Matcher m = pattern.matcher(line);
        return m.find() ? m.group(1) : fallback;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
