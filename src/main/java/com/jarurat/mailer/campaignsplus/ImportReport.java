package com.jarurat.mailer.campaignsplus;

import java.util.List;
import java.util.Map;

/**
 * What an import actually did, row by row. Totals alone are not enough: someone
 * who uploads 4,000 rows and sees "3,910 imported" will not trust the number
 * until the other 90 are named and explained.
 */
public record ImportReport(
        boolean dryRun,
        String listName,
        int rowsRead,
        int imported,
        int created,
        int updated,
        int addedToList,
        int alreadyOnList,
        int skippedDuplicate,
        int skippedSuppressed,
        int invalid,
        int failed,
        Map<String, String> columnMapping,
        List<String> unmappedColumns,
        List<RowIssue> issues,
        boolean issuesTruncated,
        List<String> notes) {

    /** One rejected row, numbered exactly the way the file numbers it. */
    public record RowIssue(int row, String email, String outcome, String reason) {}

    public static final String INVALID = "INVALID";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String SUPPRESSED = "SUPPRESSED";
    public static final String FAILED = "FAILED";
}
