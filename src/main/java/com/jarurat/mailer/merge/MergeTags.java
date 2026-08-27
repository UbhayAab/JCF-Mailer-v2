package com.jarurat.mailer.merge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One definition of what a merge tag is, shared by everything that has to agree
 * about it: the composer's test panel, the CSV discrepancy check, the template
 * library and the journey email nodes.
 *
 * Before this existed the pattern was copy-pasted into SesSender and TemplateApi.
 * That is exactly the kind of duplication that goes wrong quietly: a tag the
 * composer offered to fill in would render blank because the sender's regex was
 * a character class out of date. The regex here is the sender's regex, and
 * SesSender now reads it from this class rather than keeping its own.
 */
public final class MergeTags {

    /** Must stay identical to what the renderer substitutes on. */
    public static final Pattern PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    /**
     * Tags the sender owns. Prompting a user for these would be nonsense - the
     * unsubscribe URL is per recipient and generated at send time - and letting
     * a supplied value win would break the one-click unsubscribe Gmail requires.
     */
    public static final Set<String> RESERVED = Set.of("UNSUBSCRIBE_LINK", "TRACK");

    private MergeTags() {}

    /**
     * Every non-reserved tag across the given sources, upper-cased, in the order
     * a reader meets them. Order matters: the test panel renders one field per
     * tag and a subject-line tag appearing after a footer tag reads as a bug.
     */
    public static List<String> extract(String... sources) {
        Set<String> found = new LinkedHashSet<>();
        for (String source : sources) {
            if (source == null || source.isEmpty()) continue;
            Matcher m = PATTERN.matcher(source);
            while (m.find()) {
                String tag = m.group(1).toUpperCase(Locale.ROOT);
                if (!RESERVED.contains(tag)) found.add(tag);
            }
        }
        return new ArrayList<>(found);
    }

    /** True when the text uses a tag the caller cannot supply a value for. */
    public static List<String> missingFrom(Map<String, String> supplied, String... sources) {
        Set<String> have = new LinkedHashSet<>();
        if (supplied != null) {
            supplied.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) have.add(k.toUpperCase(Locale.ROOT));
            });
        }
        List<String> missing = new ArrayList<>();
        for (String tag : extract(sources)) if (!have.contains(tag)) missing.add(tag);
        return missing;
    }

    /**
     * A plausible value for a tag whose name we recognise, so "fill with sample
     * data" produces something that looks like a real email rather than a row of
     * the word "sample". Falls back to the tag's own words title-cased, which
     * reads better in a test than an empty string and is obviously not real.
     */
    public static String sampleFor(String tag, String testAddress) {
        if (tag == null) return "";
        String key = tag.toUpperCase(Locale.ROOT);
        return switch (key) {
            case "EMAIL", "EMAIL_ADDRESS", "TO" -> testAddress == null ? "person@example.com" : testAddress;
            case "NAME", "FULL_NAME", "CONTACT_NAME" -> "Dr. Akanksha Chichra";
            case "FIRST_NAME", "FIRSTNAME", "FNAME" -> "Dr. Akanksha";
            case "LAST_NAME", "LASTNAME", "SURNAME" -> "Chichra";
            case "COMPANY", "ORGANISATION", "ORGANIZATION", "HOSPITAL", "CLINIC" -> "Tata Memorial Hospital";
            case "CITY" -> "Mumbai";
            case "PHONE", "MOBILE" -> "+91 98200 00000";
            case "ROLE", "DESIGNATION", "TITLE" -> "Consultant Oncologist";
            case "DATE", "EVENT_DATE", "INTERVIEW_DATE" -> LocalDate.now().plusDays(7)
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
            case "TIME", "EVENT_TIME", "INTERVIEW_TIME" -> "11:00 IST";
            case "OTP", "CODE", "OTP_CODE" -> "394 812";
            case "LINK", "URL", "EVENT_LINK", "REGISTRATION_LINK" -> "https://jarurat.care/";
            case "SENDER_NAME", "FROM_NAME" -> "Jarurat Care Foundation";
            default -> titleCase(key);
        };
    }

    /** A full sample map for every tag in the given sources. */
    public static Map<String, String> sampleMap(String testAddress, String... sources) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String tag : extract(sources)) out.put(tag, sampleFor(tag, testAddress));
        return out;
    }

    /** "INTERVIEW_DATE" -> "Interview Date" */
    private static String titleCase(String tag) {
        String[] words = tag.split("_+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0)))
              .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? tag : sb.toString();
    }

    /**
     * Pulls the merge values out of a form post. The console cannot send a JSON
     * body without giving up the CSRF header wiring that every other call uses,
     * so a variable-length map arrives as merge.NAME=..., merge.CITY=... and is
     * unpacked here.
     */
    public static Map<String, String> fromParams(Map<String, String> params, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) return out;
        params.forEach((key, value) -> {
            if (key != null && key.startsWith(prefix) && key.length() > prefix.length()) {
                out.put(key.substring(prefix.length()).toUpperCase(Locale.ROOT), value == null ? "" : value);
            }
        });
        return out;
    }
}
