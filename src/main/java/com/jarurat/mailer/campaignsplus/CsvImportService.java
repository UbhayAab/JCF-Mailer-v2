package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.models.MailingList;
import com.jarurat.mailer.repositories.MailingListRepository;
import com.jarurat.mailer.services.SesSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A CSV importer for files real people actually produce: Excel with a BOM and
 * CRLF, Google Contacts with "Anil Sharma &lt;anil@x.org&gt;" in one cell, an
 * export whose columns are in whatever order the last person left them.
 *
 * Nothing is held in memory except the current chunk, and every rejected row
 * comes back with its file row number and a reason, because an import you
 * cannot audit is an import nobody trusts.
 */
@Service
public class CsvImportService {

    private static final int CHUNK = 200;
    private static final int DEFAULT_MAX_ISSUES = 500;
    private static final int MAX_EMAIL_LENGTH = 254; // RFC 5321 path limit
    private static final int NO_EMAIL_GIVE_UP = 25;  // rows to try before calling the file unusable

    // Spreadsheets leak these into exported cells. Written as code points so the
    // source file stays readable ASCII.
    private static final char ZERO_WIDTH = (char) 0x200B;
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /** Header spellings seen in the wild, normalised to letters and digits only. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        for (String a : new String[]{"email", "emailaddress", "emailaddress1", "emailid", "mail",
                "mailid", "primaryemail", "workemail", "personalemail", "contactemail", "emailid1"})
            ALIASES.put(a, "email");
        for (String a : new String[]{"firstname", "fname", "givenname", "first", "forename"})
            ALIASES.put(a, "firstName");
        for (String a : new String[]{"lastname", "lname", "surname", "familyname", "last"})
            ALIASES.put(a, "lastName");
        for (String a : new String[]{"name", "fullname", "contactname", "displayname", "personname"})
            ALIASES.put(a, "name");
        for (String a : new String[]{"phone", "phonenumber", "mobile", "mobilenumber", "contactnumber",
                "contactno", "whatsapp", "cell", "telephone", "tel"})
            ALIASES.put(a, "phone");
        for (String a : new String[]{"company", "companyname", "organisation", "organization", "org",
                "hospital", "institute", "institution", "employer", "clinic", "ngo"})
            ALIASES.put(a, "company");
    }

    private final ImportWriter writer;
    private final CampaignsPlusRepository queries;
    private final MailingListRepository lists;
    private final long maxBytes;
    private final int maxRows;

    public CsvImportService(ImportWriter writer,
                            CampaignsPlusRepository queries,
                            MailingListRepository lists,
                            @Value("${mailer.import.maxBytes:20971520}") long maxBytes,
                            @Value("${mailer.import.maxRows:200000}") int maxRows) {
        this.writer = writer;
        this.queries = queries;
        this.lists = lists;
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
    }

    public long getMaxBytes() { return maxBytes; }
    public int getMaxRows() { return maxRows; }

    /** The header spellings the importer understands, for the upload screen. */
    public Map<String, List<String>> understoodColumns() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        ALIASES.forEach((alias, field) ->
                out.computeIfAbsent(field, k -> new ArrayList<>()).add(alias));
        return out;
    }

    public ImportReport importFile(MultipartFile file, Long listId, String source, boolean dryRun)
            throws IOException {
        return importFile(file, listId, source, dryRun, DEFAULT_MAX_ISSUES);
    }

    // ------------------------------------------------------------------
    // Profiling
    // ------------------------------------------------------------------

    /**
     * Reads the first few rows and reports what the file looks like, so the caller
     * can show a mapping table instead of asking someone to trust a guess.
     *
     * Deliberately stops after sampleRows: this runs the moment a file is chosen
     * and has to feel instant on a 50k row export. Exact counts are the dry run's
     * job, not this one's.
     */
    public ImportProfile profile(MultipartFile file, int sampleRows) throws IOException {
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Choose a CSV file first.");
        if (file.getSize() > maxBytes)
            throw new IllegalArgumentException("That file is " + size(file.getSize())
                    + " and the limit is " + size(maxBytes) + ". Split it and import the parts.");

        int wanted = Math.max(1, Math.min(200, sampleRows));
        List<String> notes = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        List<String> headerCells = new ArrayList<>();
        boolean headerPresent = false;
        boolean bomStripped;
        char delimiter;
        boolean moreRows = false;

        try (CountingInputStream counting = new CountingInputStream(file.getInputStream(), maxBytes);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(counting, StandardCharsets.UTF_8), 32768)) {

            bomStripped = stripBomReported(reader);
            delimiter = sniffDelimiter(reader);
            if (delimiter == ';') notes.add("Semicolon delimited file.");
            if (delimiter == '\t') notes.add("Tab delimited file.");
            if (bomStripped) notes.add("A byte order mark was stripped. Excel adds it.");

            CsvStream csv = new CsvStream(reader, delimiter);
            List<String> cells;
            boolean first = true;

            while ((cells = csv.next()) != null) {
                if (isEmpty(cells)) continue;
                if (first) {
                    first = false;
                    // Header.detect returns null when the row holds a real address,
                    // which is the same test the importer uses. Reusing it means the
                    // preview and the import can never disagree about row one.
                    if (Header.detect(cells) != null) {
                        headerPresent = true;
                        for (String c : cells) headerCells.add(tidy(c));
                        continue;
                    }
                }
                if (rows.size() >= wanted) { moreRows = true; break; }
                rows.add(cells);
            }
        } catch (LimitExceeded e) {
            throw new IllegalArgumentException("That file is over the " + size(maxBytes)
                    + " limit. Split it and import the parts.");
        }

        if (rows.isEmpty() && !headerPresent)
            throw new IllegalArgumentException("That file has no rows in it.");
        if (!headerPresent) notes.add("No header row, so columns are numbered rather than named.");

        int columnCount = headerCells.size();
        for (List<String> row : rows) columnCount = Math.max(columnCount, row.size());
        if (columnCount > 50) {
            columnCount = 50;
            notes.add("Only the first 50 columns are shown.");
        }

        Header detected = headerPresent ? Header.detect(headerCells) : null;
        List<ImportProfile.Column> columns = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            int blanks = 0, emails = 0;
            List<String> examples = new ArrayList<>();
            for (List<String> row : rows) {
                String value = cell(row, i);
                if (value == null || value.isBlank()) { blanks++; continue; }
                String candidate = cleanEmail(value);
                if (candidate != null && SesSender.EMAIL_OK.matcher(candidate).matches()) emails++;
                if (examples.size() < 3) examples.add(trim(value, 60));
            }
            columns.add(new ImportProfile.Column(i,
                    i < headerCells.size() ? headerCells.get(i) : "",
                    detected != null ? detected.fieldAt(i) : guessFieldAt(i, rows),
                    blanks, emails, examples));
        }

        return new ImportProfile(file.getOriginalFilename(), String.valueOf(delimiter),
                headerPresent, bomStripped, columnCount, columns, rows, rows.size(),
                moreRows, file.getSize(), notes);
    }

    /**
     * For a headerless file the only evidence is the data, so a column that holds
     * addresses is the email column and the first other column carrying text is
     * the name. This mirrors what importFile does when no header is found.
     */
    private static String guessFieldAt(int index, List<List<String>> rows) {
        if (rows.isEmpty()) return null;
        int emailColumn = -1;
        for (List<String> row : rows) {
            int guess = inferEmailColumn(row);
            if (guess >= 0) { emailColumn = guess; break; }
        }
        if (emailColumn < 0) return null;
        if (index == emailColumn) return "email";
        int nameColumn = firstOtherTextColumn(rows.get(0), emailColumn);
        return index == nameColumn ? "name" : null;
    }

    public ImportReport importFile(MultipartFile file, Long listId, String source,
                                   boolean dryRun, int maxIssues) throws IOException {
        return importFile(file, listId, source, dryRun, maxIssues, Map.of());
    }

    /**
     * columnMapping is the review screen's answer to "which column is which",
     * keyed by zero-based column index. Empty means fall back to detection, which
     * is what every existing caller does.
     */
    public ImportReport importFile(MultipartFile file, Long listId, String source,
                                   boolean dryRun, int maxIssues,
                                   Map<Integer, String> columnMapping) throws IOException {
        if (columnMapping == null) columnMapping = Map.of();
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Choose a CSV file first.");
        if (file.getSize() > maxBytes)
            throw new IllegalArgumentException("That file is " + size(file.getSize())
                    + " and the limit is " + size(maxBytes) + ". Split it and import the parts.");

        String listName = null;
        if (listId != null) {
            listName = lists.findById(listId).map(MailingList::getName)
                    .orElseThrow(() -> new IllegalArgumentException("No such list."));
        }
        String finalSource = (source == null || source.isBlank())
                ? (listName == null ? "CSV import" : listName) : source.trim();

        Tally tally = new Tally(Math.max(0, Math.min(5000, maxIssues)));
        List<String> notes = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        Map<String, Integer> seenAt = new HashMap<>();
        List<ImportWriter.PendingRow> batch = new ArrayList<>(CHUNK);
        ImportWriter.ChunkResult totals = ImportWriter.ChunkResult.empty();

        Set<String> suppressed = lowerSet(queries.allSuppressedEmails());
        Set<String> unmailable = lowerSet(queries.allUnmailableEmails());

        try (CountingInputStream counting = new CountingInputStream(file.getInputStream(), maxBytes);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(counting, StandardCharsets.UTF_8), 32768)) {

            stripBom(reader);
            char delimiter = sniffDelimiter(reader);
            if (delimiter == ';') notes.add("Semicolon delimited file.");
            if (delimiter == '\t') notes.add("Tab delimited file.");

            CsvStream csv = new CsvStream(reader, delimiter);
            Header header = null;
            int row = 0;
            List<String> cells;

            while ((cells = csv.next()) != null) {
                row++;
                if (isEmpty(cells)) continue;

                if (header == null) {
                    Header detected = Header.detect(cells);
                    boolean headerRow = detected != null;

                    if (!columnMapping.isEmpty()) {
                        // A mapping chosen in the review screen beats the alias table.
                        // The aliases are good but they are still a guess, and a guess
                        // nobody can correct is why imports get abandoned halfway.
                        List<String> headerCells = new ArrayList<>();
                        if (headerRow) for (String c : cells) headerCells.add(tidy(c));
                        header = Header.fromOverrides(columnMapping, headerCells);
                        if (header.email < 0)
                            throw new IllegalArgumentException(
                                    "Choose which column holds the email address.");
                        notes.add("Columns mapped by hand rather than detected.");
                        header.describe(mapping, unmapped);
                        if (headerRow) continue; // the header is not a person
                    } else if (headerRow) {
                        header = detected;
                        notes.add("Header row read from row " + row + ".");
                        header.describe(mapping, unmapped);
                        continue; // the header is not a person
                    } else {
                        header = new Header();
                        int guess = inferEmailColumn(cells);
                        if (guess < 0)
                            throw new IllegalArgumentException("No email column found. The first row has no "
                                    + "recognisable heading and no email address in it.");
                        header.email = guess;
                        header.name = firstOtherTextColumn(cells, guess);
                        notes.add("No header row. Column " + (guess + 1) + " was read as the email address.");
                        mapping.put("email", "column " + (guess + 1));
                        if (header.name >= 0) mapping.put("name", "column " + (header.name + 1));
                    }
                }

                if (tally.rowsRead >= maxRows) {
                    notes.add("Stopped at the " + maxRows + " row cap. Split the file and import the rest.");
                    break;
                }
                tally.rowsRead++;

                if (header.email < 0) {
                    int guess = inferEmailColumn(cells);
                    if (guess < 0) {
                        tally.invalid++;
                        tally.issue(row, "", ImportReport.INVALID,
                                "No column in this file holds an email address.");
                        // Every remaining row would fail identically, so stop
                        // rather than hand back ten thousand copies of one problem.
                        if (tally.rowsRead >= NO_EMAIL_GIVE_UP) throw noEmailColumn(tally.rowsRead);
                        continue;
                    }
                    header.email = guess;
                    notes.add("No email heading matched, so column " + (guess + 1)
                            + " was used because it holds addresses.");
                    mapping.put("email", "column " + (guess + 1));
                }

                String raw = cell(cells, header.email);
                String email = cleanEmail(raw);
                if (email == null) {
                    tally.invalid++;
                    tally.issue(row, "", ImportReport.INVALID, "The email column is empty.");
                    continue;
                }
                if (email.length() > MAX_EMAIL_LENGTH) {
                    tally.invalid++;
                    tally.issue(row, trim(email, 80), ImportReport.INVALID,
                            "Longer than " + MAX_EMAIL_LENGTH + " characters, so no mail server will accept it.");
                    continue;
                }
                if (!SesSender.EMAIL_OK.matcher(email).matches()) {
                    tally.invalid++;
                    tally.issue(row, trim(email, 80), ImportReport.INVALID, "Not a valid email address.");
                    continue;
                }

                Integer first = seenAt.putIfAbsent(email, row);
                if (first != null) {
                    tally.duplicate++;
                    tally.issue(row, email, ImportReport.DUPLICATE,
                            "Same address already appeared on row " + first + " of this file.");
                    continue;
                }
                if (suppressed.contains(email)) {
                    tally.suppressed++;
                    tally.issue(row, email, ImportReport.SUPPRESSED,
                            "On the suppression list: unsubscribed, bounced or complained. Refused.");
                    continue;
                }
                if (unmailable.contains(email)) {
                    tally.suppressed++;
                    tally.issue(row, email, ImportReport.SUPPRESSED,
                            "Already in the audience with a status that is not SUBSCRIBED. Refused.");
                    continue;
                }

                String firstName = cell(cells, header.firstName);
                String lastName = cell(cells, header.lastName);
                if (firstName == null && header.name >= 0) {
                    String whole = cell(cells, header.name);
                    if (whole != null) {
                        int comma = whole.indexOf(',');
                        int space = whole.indexOf(' ');
                        if (comma > 0) {
                            // Outlook and most CRMs write a single name column as "Sharma, Anil"
                            String left = whole.substring(0, comma).trim();
                            String right = whole.substring(comma + 1).trim();
                            firstName = right.isEmpty() ? whole : right;
                            if (lastName == null && !right.isEmpty()) lastName = left;
                        } else if (space > 0) {
                            firstName = whole.substring(0, space).trim();
                            if (lastName == null) lastName = whole.substring(space + 1).trim();
                        } else {
                            firstName = whole;
                        }
                    }
                }

                batch.add(new ImportWriter.PendingRow(row, email, firstName, lastName,
                        cell(cells, header.phone), cell(cells, header.company)));
                tally.accepted++;

                if (batch.size() >= CHUNK) {
                    totals = totals.plus(flush(batch, listId, finalSource, dryRun, tally));
                    batch.clear();
                }
            }

            if (header != null && header.email < 0 && tally.rowsRead > 0)
                throw noEmailColumn(tally.rowsRead);

            if (!batch.isEmpty()) {
                totals = totals.plus(flush(batch, listId, finalSource, dryRun, tally));
                batch.clear();
            }
        } catch (LimitExceeded e) {
            throw new IllegalArgumentException("That file is over the " + size(maxBytes)
                    + " limit. Split it and import the parts.");
        }

        if (dryRun) notes.add("Dry run. Nothing was written.");
        if (tally.failed > 0) notes.add(tally.failed + " row(s) could not be written. "
                + "Rows written before them are already saved.");
        if (tally.truncated) notes.add("Only the first " + tally.issues.size()
                + " rejected rows are listed. The counts above cover all of them.");
        notes.add("Row numbers count records as a spreadsheet does, header included.");

        return new ImportReport(dryRun, listName, tally.rowsRead,
                tally.accepted - tally.failed,
                totals.created(), totals.updated(), totals.addedToList(), totals.alreadyOnList(),
                tally.duplicate, tally.suppressed, tally.invalid, tally.failed,
                mapping, unmapped, tally.issues, tally.truncated, notes);
    }

    /**
     * One transaction per chunk. If the chunk fails the rows are retried one at
     * a time, so a single conflicting address cannot cost the other 199.
     */
    private ImportWriter.ChunkResult flush(List<ImportWriter.PendingRow> batch, Long listId,
                                           String source, boolean dryRun, Tally tally) {
        if (dryRun) return writer.preview(batch, listId);
        try {
            return writer.write(batch, listId, source);
        } catch (RuntimeException chunkFailed) {
            ImportWriter.ChunkResult rescued = ImportWriter.ChunkResult.empty();
            for (ImportWriter.PendingRow row : batch) {
                try {
                    rescued = rescued.plus(writer.writeOne(row, listId, source));
                } catch (RuntimeException rowFailed) {
                    tally.failed++;
                    tally.issue(row.row(), row.email(), ImportReport.FAILED,
                            "Database refused this row: " + rootMessage(rowFailed));
                }
            }
            return rescued;
        }
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Column positions, resolved from whatever headings the file happens to use. */
    private static final class Header {
        int email = -1, firstName = -1, lastName = -1, name = -1, phone = -1, company = -1;
        final List<String> ignored = new ArrayList<>();
        final Map<String, String> matched = new LinkedHashMap<>();

        /**
         * A row holding a valid address is data, never a header. That single
         * test settles the headerless case without guessing at wording.
         */
        static Header detect(List<String> cells) {
            for (String c : cells) {
                String candidate = cleanEmail(c);
                if (candidate != null && SesSender.EMAIL_OK.matcher(candidate).matches()) return null;
            }
            Header h = new Header();
            for (int i = 0; i < cells.size(); i++) {
                String raw = tidy(cells.get(i));
                String field = ALIASES.get(norm(raw));
                if (field == null || h.taken(field)) {
                    if (!raw.isEmpty()) h.ignored.add(raw);
                    continue;
                }
                h.set(field, i);
                h.matched.put(field, raw);
            }
            return h;
        }

        boolean taken(String field) {
            return switch (field) {
                case "email" -> email >= 0;
                case "firstName" -> firstName >= 0;
                case "lastName" -> lastName >= 0;
                case "name" -> name >= 0;
                case "phone" -> phone >= 0;
                case "company" -> company >= 0;
                default -> true;
            };
        }

        void set(String field, int index) {
            switch (field) {
                case "email" -> email = index;
                case "firstName" -> firstName = index;
                case "lastName" -> lastName = index;
                case "name" -> name = index;
                case "phone" -> phone = index;
                case "company" -> company = index;
                default -> { }
            }
        }

        void describe(Map<String, String> mapping, List<String> unmapped) {
            mapping.putAll(matched);
            unmapped.addAll(ignored);
        }

        /** Which field this column was placed in, or null. Reverse of set(). */
        String fieldAt(int index) {
            if (index < 0) return null;
            if (index == email) return "email";
            if (index == firstName) return "firstName";
            if (index == lastName) return "lastName";
            if (index == name) return "name";
            if (index == phone) return "phone";
            if (index == company) return "company";
            return null;
        }

        /**
         * A mapping the user chose by hand, which beats anything the aliases would
         * have guessed. Later duplicates for one field are ignored rather than
         * silently overwriting, so "two columns both set to first name" stays
         * detectable by the caller instead of quietly resolving to the last one.
         */
        static Header fromOverrides(Map<Integer, String> overrides, List<String> headerCells) {
            Header h = new Header();
            List<Integer> indexes = new ArrayList<>(overrides.keySet());
            java.util.Collections.sort(indexes);
            for (Integer index : indexes) {
                String field = overrides.get(index);
                if (field == null || field.isBlank() || index < 0) continue;
                if (h.taken(field)) continue;
                h.set(field, index);
                h.matched.put(field, index < headerCells.size() && !headerCells.get(index).isBlank()
                        ? headerCells.get(index) : "column " + (index + 1));
            }
            for (int i = 0; i < headerCells.size(); i++) {
                if (h.fieldAt(i) == null && !headerCells.get(i).isBlank()) h.ignored.add(headerCells.get(i));
            }
            return h;
        }
    }

    /** Reads records, not lines: a quoted cell may contain commas and newlines. */
    private static final class CsvStream {
        private static final int NONE = -2;
        private final Reader in;
        private final char delimiter;
        private int pushback = NONE;

        CsvStream(Reader in, char delimiter) {
            this.in = in;
            this.delimiter = delimiter;
        }

        private int read() throws IOException {
            if (pushback != NONE) {
                int c = pushback;
                pushback = NONE;
                return c;
            }
            return in.read();
        }

        private void push(int c) { pushback = c; }

        List<String> next() throws IOException {
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false, any = false;
            int c;
            while ((c = read()) != -1) {
                any = true;
                char ch = (char) c;
                if (inQuotes) {
                    if (ch == '"') {
                        int nxt = read();
                        if (nxt == '"') cur.append('"');
                        else { inQuotes = false; push(nxt); }
                    } else {
                        cur.append(ch);
                    }
                } else if (ch == '"') {
                    inQuotes = true;
                } else if (ch == delimiter) {
                    cells.add(cur.toString());
                    cur.setLength(0);
                } else if (ch == '\n') {
                    cells.add(cur.toString());
                    return cells;
                } else if (ch == '\r') {
                    int nxt = read();
                    if (nxt != '\n') push(nxt);
                    cells.add(cur.toString());
                    return cells;
                } else {
                    cur.append(ch);
                }
            }
            if (!any) return null;
            cells.add(cur.toString());
            return cells;
        }
    }

    /** Refuses to read past the cap even if the upload lied about its length. */
    private static final class CountingInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        CountingInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c != -1) tick(1);
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) tick(n);
            return n;
        }

        private void tick(long n) throws IOException {
            count += n;
            if (count > limit) throw new LimitExceeded("Upload exceeded " + limit + " bytes.");
        }
    }

    private static final class LimitExceeded extends IOException {
        LimitExceeded(String message) { super(message); }
    }

    private static void stripBom(BufferedReader reader) throws IOException {
        stripBomReported(reader);
    }

    /** Same thing, but says whether it found one, so the profile can report it. */
    private static boolean stripBomReported(BufferedReader reader) throws IOException {
        reader.mark(1);
        int first = reader.read();
        if (first == 0xFEFF) return true;
        reader.reset();
        return false;
    }

    /** Excel in some locales writes semicolons, exports from BI tools write tabs. */
    private static char sniffDelimiter(BufferedReader reader) throws IOException {
        reader.mark(8192);
        char[] buf = new char[4096];
        int n = reader.read(buf, 0, buf.length);
        reader.reset();
        if (n <= 0) return ',';

        int comma = 0, semi = 0, tab = 0;
        boolean quoted = false;
        for (int i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '"') quoted = !quoted;
            else if (!quoted) {
                if (c == '\n') break;
                if (c == ',') comma++;
                else if (c == ';') semi++;
                else if (c == '\t') tab++;
            }
        }
        if (semi > comma && semi >= tab) return ';';
        if (tab > comma && tab > semi) return '\t';
        return ',';
    }

    private static int inferEmailColumn(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            String candidate = cleanEmail(cells.get(i));
            if (candidate != null && SesSender.EMAIL_OK.matcher(candidate).matches()) return i;
        }
        return -1;
    }

    private static int firstOtherTextColumn(List<String> cells, int skip) {
        for (int i = 0; i < cells.size(); i++) {
            if (i == skip) continue;
            String v = cells.get(i).trim();
            if (!v.isEmpty() && v.chars().anyMatch(Character::isLetter)) return i;
        }
        return -1;
    }

    /** Handles "Anil Sharma <anil@x.org>", "mailto:", stray spaces and zero width junk. */
    static String cleanEmail(String raw) {
        if (raw == null) return null;
        String v = raw.replace(String.valueOf(ZERO_WIDTH), "")
                .replace(String.valueOf(BYTE_ORDER_MARK), "").trim();
        if (v.isEmpty()) return null;
        int lt = v.indexOf('<'), gt = v.lastIndexOf('>');
        if (lt >= 0 && gt > lt) v = v.substring(lt + 1, gt).trim();
        if (v.regionMatches(true, 0, "mailto:", 0, 7)) v = v.substring(7).trim();
        if (v.isEmpty()) return null;
        return v.toLowerCase(Locale.ROOT);
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cell(List<String> cells, int index) {
        if (index < 0 || index >= cells.size()) return null;
        String v = tidy(cells.get(index));
        return v.isEmpty() ? null : v;
    }

    /**
     * Collapses the whitespace a quoted cell is allowed to contain. A name that
     * spans two lines in the spreadsheet must not greet someone across two lines
     * in the email.
     */
    private static String tidy(String raw) {
        if (raw == null) return "";
        return raw.replace(String.valueOf(ZERO_WIDTH), "")
                .replace(String.valueOf(BYTE_ORDER_MARK), "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isEmpty(List<String> cells) {
        for (String c : cells) if (!c.trim().isEmpty()) return false;
        return true;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new HashSet<>(Math.max(16, values.size() * 2));
        for (String v : values) if (v != null) out.add(v.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return trim(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), 200);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String size(long bytes) {
        return bytes >= 1048576
                ? String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : String.format(Locale.ROOT, "%.0f KB", Math.ceil(bytes / 1024.0));
    }

    private static IllegalArgumentException noEmailColumn(int rowsRead) {
        return new IllegalArgumentException("No column in this file holds an email address. Checked "
                + rowsRead + " row(s). Add a heading called Email, or put the addresses in their own column.");
    }

    /** Running counts plus the capped list of rejections. */
    private static final class Tally {
        final List<ImportReport.RowIssue> issues = new ArrayList<>();
        final int cap;
        int rowsRead, accepted, failed, duplicate, suppressed, invalid;
        boolean truncated;

        Tally(int cap) { this.cap = cap; }

        void issue(int row, String email, String outcome, String reason) {
            if (issues.size() < cap) issues.add(new ImportReport.RowIssue(row, email, outcome, reason));
            else truncated = true;
        }
    }
}
