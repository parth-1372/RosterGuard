package com.rosterguard.roster;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.domain.FieldError;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CsvParser. No Spring context needed.
 * Verifies that streaming, error reporting, header validation, and ID capture behave correctly.
 */
class CsvParserTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String HEADER =
            "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode";

    private static final String VALID_ROW =
            "E001,1,2024-01-15T08:00:00Z,Alice Smith,alice@example.com,+919000000001,,BLR,MORNING";

    private record ParseResult(List<CsvRow> rows, List<Long> errorRowNums,
                               List<List<FieldError>> errorLists) {}

    private static ParseResult parse(String csv) throws IOException {
        List<CsvRow> rows = new ArrayList<>();
        List<Long> errorRowNums = new ArrayList<>();
        List<List<FieldError>> errorLists = new ArrayList<>();
        CsvParser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                rows::add,
                (num, errs) -> { errorRowNums.add(num); errorLists.add(errs); });
        return new ParseResult(rows, errorRowNums, errorLists);
    }

    // ── 1. Basic happy path ───────────────────────────────────────────────────

    @Test
    void validRow_parsedCorrectly() throws IOException {
        ParseResult r = parse(HEADER + "\n" + VALID_ROW);

        assertThat(r.rows()).hasSize(1);
        CsvRow row = r.rows().get(0);
        assertThat(row.isValid()).isTrue();
        assertThat(row.employeeId()).isEqualTo("E001");
        assertThat(row.sourceVersion()).isEqualTo(1L);
        assertThat(row.profile().name()).isEqualTo("Alice Smith");
        assertThat(row.profile().email()).isEqualTo("alice@example.com");
        // Phone is normalised (hyphens stripped) by HrProfile canonical constructor.
        assertThat(row.profile().phone()).isEqualTo("+919000000001");
        assertThat(row.profile().officeCode()).isEqualTo("BLR");
        assertThat(row.rosterHash()).isNotNull().hasSize(64);
        assertThat(r.errorRowNums()).isEmpty();
    }

    @Test
    void emptyFile_returnsZeroRows() throws IOException {
        ParseResult r = parse("");
        assertThat(r.rows()).isEmpty();
    }

    @Test
    void headerOnly_returnsZeroRows() throws IOException {
        ParseResult r = parse(HEADER);
        assertThat(r.rows()).isEmpty();
    }

    @Test
    void multipleValidRows_allParsed() throws IOException {
        String csv = HEADER + "\n"
                + "E001,1,2024-01-15T08:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING\n"
                + "E002,2,2024-01-15T08:01:00Z,Bob,bob@example.com,+919000000002,,PUN,EVENING\n"
                + "E003,3,2024-01-15T08:02:00Z,Carol,carol@example.com,+919000000003,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(3);
        assertThat(r.rows()).allMatch(CsvRow::isValid);
        assertThat(r.errorRowNums()).isEmpty();
    }

    // ── 2. Missing/invalid fields ─────────────────────────────────────────────

    @Test
    void missingEmployeeId_rowInvalid_employeeIdNull() throws IOException {
        String csv = HEADER + "\n"
                + ",1,2024-01-15T08:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(1);
        CsvRow row = r.rows().get(0);
        assertThat(row.isValid()).isFalse();
        assertThat(row.employeeId()).isNull();
        assertThat(r.errorRowNums()).containsExactly(1L);
        assertThat(r.errorLists().get(0)).anyMatch(e -> e.field().equals("employeeId"));
    }

    @Test
    void invalidEmployeeIdFormat_notUsable() throws IOException {
        // ID contains characters outside [A-Za-z0-9_-]
        String csv = HEADER + "\n"
                + "bad id!,1,2024-01-15T08:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).employeeId()).isNull(); // not usable
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.field().equals("employeeId") && e.code().equals("INVALID_FORMAT"));
    }

    @Test
    void missingSourceVersion_rowInvalid() throws IOException {
        String csv = HEADER + "\n"
                + "E001,,2024-01-15T08:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.rows().get(0).employeeId()).isEqualTo("E001"); // ID still captured
        assertThat(r.errorLists().get(0)).anyMatch(e -> e.field().equals("sourceVersion"));
    }

    @Test
    void negativeSourceVersion_rowInvalid() throws IOException {
        String csv = HEADER + "\n"
                + "E001,-5,2024-01-15T08:00:00Z,Alice,alice@example.com,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.rows().get(0).employeeId()).isEqualTo("E001");
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.field().equals("sourceVersion") && e.code().equals("INVALID_FORMAT"));
    }

    @Test
    void invalidSourceUpdatedAt_rowInvalid() throws IOException {
        String csv = HEADER + "\n"
                + "E001,1,not-a-date,Alice,alice@example.com,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.field().equals("sourceUpdatedAt") && e.code().equals("INVALID_FORMAT"));
    }

    @Test
    void invalidEmail_rowInvalid() throws IOException {
        String csv = HEADER + "\n"
                + "E001,1,2024-01-15T08:00:00Z,Alice,not-an-email,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.rows().get(0).employeeId()).isEqualTo("E001");
        assertThat(r.errorLists().get(0)).anyMatch(e -> e.field().equals("email"));
    }

    @Test
    void invalidPhone_rowInvalid() throws IOException {
        String csv = HEADER + "\n"
                + "E001,1,2024-01-15T08:00:00Z,Alice,alice@example.com,09000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.errorLists().get(0)).anyMatch(e -> e.field().equals("phone"));
    }

    @Test
    void wrongColumnCount_reportedAsError() throws IOException {
        String csv = HEADER + "\n" + "E001,1,2024-01-15T08:00:00Z,Alice"; // only 4 columns
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.rows().get(0).employeeId()).isNull();
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.code().equals("WRONG_COLUMN_COUNT"));
    }

    // ── 3. Key invariant: ID captured even on invalid rows ────────────────────

    @Test
    void invalidRowWithUsableId_idCaptured() throws IOException {
        // Row has an invalid phone but valid employeeId — the ID must still be returned.
        String csv = HEADER + "\n"
                + "E999,1,2024-01-15T08:00:00Z,Alice,alice@example.com,BADPHONE,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).isValid()).isFalse();
        assertThat(r.rows().get(0).employeeId()).isEqualTo("E999"); // CAPTURED
        assertThat(r.errorRowNums()).containsExactly(1L);
    }

    // ── 4. CSV quoting ────────────────────────────────────────────────────────

    @Test
    void quotedFieldWithComma_parsedAsOneField() throws IOException {
        // Name contains a comma inside quotes.
        String csv = HEADER + "\n"
                + "E001,1,2024-01-15T08:00:00Z,\"Smith, Alice\",alice@example.com,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).isValid()).isTrue();
        assertThat(r.rows().get(0).profile().name()).isEqualTo("Smith, Alice");
    }

    // ── 5. Blank lines are skipped ────────────────────────────────────────────

    @Test
    void trailingBlankLines_skipped() throws IOException {
        String csv = HEADER + "\n" + VALID_ROW + "\n\n\n";
        ParseResult r = parse(csv);
        assertThat(r.rows()).hasSize(1);
    }

    // ── 6. splitCsv edge cases ────────────────────────────────────────────────

    @Test
    void splitCsv_emptyFields() {
        String[] cols = CsvParser.splitCsv("a,,c");
        assertThat(cols).containsExactly("a", "", "c");
    }

    @Test
    void splitCsv_doubleQuoteEscape() {
        String[] cols = CsvParser.splitCsv("\"he said \"\"hello\"\"\",b");
        assertThat(cols[0]).isEqualTo("he said \"hello\"");
    }

    // ── 7. Error bodies contain no raw PII ───────────────────────────────────

    @Test
    void errorList_containsNoRawValues() throws IOException {
        // Error codes and field names only — no raw input values.
        String csv = HEADER + "\n"
                + "E001,1,2024-01-15T08:00:00Z,Alice,NOT_AN_EMAIL,+919000000001,,BLR,MORNING";
        ParseResult r = parse(csv);
        List<FieldError> errors = r.errorLists().get(0);
        // Ensure no error code contains "NOT_AN_EMAIL" (the raw value).
        assertThat(errors).noneMatch(e -> e.code().contains("NOT_AN_EMAIL"));
        assertThat(errors).noneMatch(e -> e.field().contains("NOT_AN_EMAIL"));
    }

    // ── 8. Header validation & Reordering ────────────────────────────────────

    @Test
    void reorderedColumns_parsedCorrectly() throws IOException {
        String reorderedHeader = "name,employeeId,sourceVersion,sourceUpdatedAt,email,phone,managerId,officeCode,shiftCode";
        String row = "Bob Smith,E-REORDER-1,3,2024-01-15T08:00:00Z,bob@example.com,+919000000001,M1,BLR,DAY";

        ParseResult r = parse(reorderedHeader + "\n" + row);
        assertThat(r.rows()).hasSize(1);
        CsvRow parsed = r.rows().get(0);
        assertThat(parsed.isValid()).isTrue();
        assertThat(parsed.employeeId()).isEqualTo("E-REORDER-1");
        assertThat(parsed.sourceVersion()).isEqualTo(3L);
        assertThat(parsed.profile().name()).isEqualTo("Bob Smith");
        assertThat(parsed.profile().email()).isEqualTo("bob@example.com");
        assertThat(parsed.profile().officeCode()).isEqualTo("BLR");
        assertThat(r.errorRowNums()).isEmpty();
    }

    @Test
    void missingRequiredHeaderColumn_failsAtRowZero() throws IOException {
        // Missing shiftCode
        String badHeader = "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode";
        ParseResult r = parse(badHeader + "\n" + VALID_ROW);

        assertThat(r.rows()).isEmpty();
        assertThat(r.errorRowNums()).containsExactly(0L);
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.field().equals("shiftCode") && e.code().equals("MISSING_REQUIRED_COLUMN"));
    }

    @Test
    void duplicateHeaderColumn_failsAtRowZero() throws IOException {
        String badHeader = "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,employeeId";
        ParseResult r = parse(badHeader + "\n" + VALID_ROW);

        assertThat(r.rows()).isEmpty();
        assertThat(r.errorRowNums()).containsExactly(0L);
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.field().equals("employeeId") && e.code().equals("DUPLICATE_HEADER_COLUMN"));
    }

    @Test
    void unexpectedHeaderColumn_failsAtRowZero() throws IOException {
        String badHeader = "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode,extraCol";
        ParseResult r = parse(badHeader + "\n" + VALID_ROW);

        assertThat(r.rows()).isEmpty();
        assertThat(r.errorRowNums()).containsExactly(0L);
        assertThat(r.errorLists().get(0))
                .anyMatch(e -> e.field().equals("extraCol") && e.code().equals("UNEXPECTED_COLUMN"));
    }
}
