package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.services.CampaignService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cross-checks the merge tags a creative uses against the columns the audience
 * data actually has, before a blast rather than after it.
 *
 * The failure this prevents is quiet by design: an unresolved tag renders as an
 * empty string, so "Dear {{FIRST_NAME}}" becomes "Dear " and goes out to four
 * thousand doctors looking, at a glance, like a message that worked. Nothing in
 * the send path can tell the difference between a tag with no column and a tag
 * whose column happens to be blank for that person, which is why this runs at
 * import time where both are visible.
 */
@Service
public class AudienceMatchService {

    public static final String BLOCK = "BLOCK";
    public static final String WARN = "WARN";
    public static final String INFO = "INFO";

    /**
     * One finding. affectedRows is 0 when the count is not meaningful, for example
     * on a structural problem like two columns claiming the same field.
     */
    public record Discrepancy(String code, String severity, String tag, String column,
                              String message, long affectedRows) {}

    /**
     * Which merge tags each importable field can satisfy. A single "name" column
     * feeds three tags because the importer splits it, which is why the mapping is
     * one-to-many rather than a straight rename.
     */
    private static List<String> tagsFor(String field) {
        if (field == null) return List.of();
        return switch (field) {
            case "email" -> List.of("EMAIL");
            case "name" -> List.of("NAME", "FIRST_NAME", "LAST_NAME");
            case "firstName" -> List.of("FIRST_NAME", "NAME");
            case "lastName" -> List.of("LAST_NAME", "NAME");
            case "phone" -> List.of("PHONE");
            case "company" -> List.of("COMPANY");
            default -> List.of();
        };
    }

    /**
     * @param tags    merge tags used in the creative, uppercase, reserved removed
     * @param profile what the file looks like
     * @param mapping column index -> field name, blank or absent meaning ignore
     * @param dryRun  the counting pass, so the numbers quoted are the real ones
     */
    public List<Discrepancy> reconcile(List<String> tags, ImportProfile profile,
                                       Map<Integer, String> mapping, ImportReport dryRun) {
        List<Discrepancy> out = new ArrayList<>();
        if (profile == null) return out;

        Map<Integer, String> effective = mapping == null ? Map.of() : mapping;
        Set<String> mappedFields = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (String field : effective.values()) {
            if (field == null || field.isBlank()) continue;
            if (!mappedFields.add(field)) duplicated.add(field);
        }

        // ---------- blockers ----------

        if (!mappedFields.contains("email")) {
            out.add(new Discrepancy("NO_EMAIL_COLUMN", BLOCK, null, null,
                    "We cannot find an email address column. Choose which column holds the address, "
                    + "or add a heading called Email to your file.", 0));
        }
        for (String field : duplicated) {
            out.add(new Discrepancy("DUPLICATE_MAPPING", BLOCK, null, field,
                    "Two columns are both set to \"" + label(field) + "\". Pick one and set the other "
                    + "to ignore.", 0));
        }
        if (dryRun != null && dryRun.imported() == 0 && dryRun.rowsRead() > 0) {
            if (dryRun.skippedSuppressed() > 0 && dryRun.invalid() == 0 && dryRun.skippedDuplicate() == 0) {
                out.add(new Discrepancy("ALL_SUPPRESSED", BLOCK, null, null,
                        "Every address in this file has unsubscribed, bounced or complained. "
                        + "Nothing would be sent.", dryRun.skippedSuppressed()));
            } else {
                out.add(new Discrepancy("NO_VALID_ROWS", BLOCK, null, null,
                        "Not one row in this file can be mailed. " + dryRun.rowsRead()
                        + " read, all rejected. Open the breakdown to see why.", dryRun.rowsRead()));
            }
        }

        // ---------- per tag ----------

        Set<String> satisfied = new LinkedHashSet<>();
        for (String field : mappedFields) satisfied.addAll(tagsFor(field));

        long recipients = dryRun == null ? 0 : dryRun.imported();
        Set<String> sendable = CampaignService.SENDABLE_FIELDS.keySet();

        for (String raw : tags == null ? List.<String>of() : tags) {
            String tag = raw.toUpperCase(Locale.ROOT);
            if (!sendable.contains(tag)) {
                out.add(new Discrepancy("TAG_NOT_SENDABLE", WARN, tag, null,
                        "Your email uses {{" + tag + "}}. A campaign send can only fill "
                        + String.join(", ", sendable) + ", so this renders blank for everyone "
                        + "even when your file has that column.", recipients));
                continue;
            }
            if (!satisfied.contains(tag)) {
                out.add(new Discrepancy("TAG_WITHOUT_COLUMN", WARN, tag, null,
                        "Your email uses {{" + tag + "}} but no column is mapped to it."
                        + (recipients > 0 ? " All " + recipients + " recipients will see a blank there."
                                          : " Every recipient will see a blank there."), recipients));
                continue;
            }
            long blanks = blanksBehind(tag, effective, profile);
            if (blanks > 0) {
                out.add(new Discrepancy("TAG_PARTIALLY_BLANK", WARN, tag, null,
                        blanks + " of the " + profile.sampledRows() + " rows we sampled have no value "
                        + "for this, so {{" + tag + "}} is blank for those people.", blanks));
            }
        }

        // ---------- notes ----------

        Set<String> tagSet = new LinkedHashSet<>();
        for (String raw : tags == null ? List.<String>of() : tags) tagSet.add(raw.toUpperCase(Locale.ROOT));

        for (ImportProfile.Column column : profile.columns()) {
            String field = effective.get(column.index());
            String name = column.header() == null || column.header().isBlank()
                    ? "Column " + (column.index() + 1) : column.header();
            if (field == null || field.isBlank()) {
                if (!column.header().isBlank()) {
                    out.add(new Discrepancy("COLUMN_UNUSED", INFO, null, name,
                            "The column \"" + name + "\" is not being imported. Nothing uses it.", 0));
                }
                continue;
            }
            boolean used = false;
            for (String candidate : tagsFor(field)) if (tagSet.contains(candidate)) { used = true; break; }
            if (!used) {
                out.add(new Discrepancy("COLUMN_IMPORTED_UNUSED", INFO, null, name,
                        label(field) + " is imported and kept on the subscriber record, but your email "
                        + "never uses it.", 0));
            }
        }
        return out;
    }

    /** True when any finding would stop the import. */
    public static boolean blocked(List<Discrepancy> findings) {
        for (Discrepancy d : findings) if (BLOCK.equals(d.severity())) return true;
        return false;
    }

    /**
     * How many sampled rows are blank in whichever column feeds this tag. Sampled,
     * not total, because the profile only read the head of the file: the message
     * says "of the rows we sampled" rather than implying a full count it does not
     * have.
     */
    private static long blanksBehind(String tag, Map<Integer, String> mapping, ImportProfile profile) {
        for (Map.Entry<Integer, String> entry : mapping.entrySet()) {
            if (entry.getValue() == null || !tagsFor(entry.getValue()).contains(tag)) continue;
            for (ImportProfile.Column column : profile.columns()) {
                if (column.index() == entry.getKey()) return column.blankCount();
            }
        }
        return 0;
    }

    private static String label(String field) {
        return switch (field) {
            case "email" -> "email";
            case "firstName" -> "first name";
            case "lastName" -> "last name";
            case "name" -> "name";
            case "phone" -> "phone";
            case "company" -> "company";
            default -> field;
        };
    }
}
