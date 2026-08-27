package com.jarurat.mailer.campaignsplus;

import java.util.List;

/**
 * What a file looks like, read from its first few rows only.
 *
 * This exists so the composer can show a column mapping table before committing
 * to anything. The importer's own header detection is good but it is a guess, and
 * a guess the user cannot see or correct is the reason imports get abandoned
 * halfway. The profile turns that guess into a preselected dropdown.
 *
 * Counts here are from the sample, never from the file. Exact totals come from a
 * dry run, which does a full streaming pass and writes nothing.
 */
public record ImportProfile(
        String sourceName,
        String delimiter,
        boolean headerPresent,
        boolean bomStripped,
        int columnCount,
        List<Column> columns,
        List<List<String>> sampleRows,
        int sampledRows,
        boolean moreRows,
        long bytes,
        List<String> notes) {

    /**
     * One column as the file has it. detectedField is the importer's own guess and
     * becomes the preselected option; null means it could not place the column.
     */
    public record Column(
            int index,
            String header,
            String detectedField,
            int blankCount,
            int validEmailCount,
            List<String> examples) {}

    /** The fields a column can be mapped to. Order is the order of the dropdown. */
    public static final List<String> TARGET_FIELDS =
            List.of("email", "firstName", "lastName", "name", "phone", "company");
}
