package com.rosterguard.roster;

import com.rosterguard.domain.FieldError;
import com.rosterguard.domain.HrProfile;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Streams a nightly CSV file row by row, emitting either a valid {@link CsvRow} or a list of
 * field errors. The file is never fully loaded into memory: only one line is held at a time.
 *
 * <h3>Expected column order</h3>
 * <pre>
 * employeeId, sourceVersion, sourceUpdatedAt, name, email, phone, managerId, officeCode, shiftCode
 * </pre>
 * The header row is required and skipped automatically.
 *
 * <h3>Error reporting</h3>
 * Field names and error codes only — never the raw value that caused the error (no PII).
 */
public final class CsvParser {

    // Expected column count (header row defines the contract).
    private static final int EXPECTED_COLUMNS = 9;

    // Mirrors the HrEventValidator patterns.
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private CsvParser() {}

    /**
     * Parses the CSV stream row by row. For each row the appropriate callback fires:
     * <ul>
     *   <li>{@code onRow} — a fully or partially parsed row (with usable employeeId if present)</li>
     *   <li>{@code onError} — (rowNumber, errors) for rows that cannot be staged</li>
     * </ul>
     * Both callbacks fire for invalid rows that still have a usable employeeId (the ID is captured
     * for {@code import_seen_id} even when the row cannot be staged).
     *
     * @param stream the raw CSV bytes; closed by the caller
     * @param onRow  called for every row, valid or invalid (the row object carries validity state)
     * @param onError called for every invalid row with the list of field errors
     * @return total data rows seen (excluding header)
     */
    public static long parse(InputStream stream,
                             java.util.function.Consumer<CsvRow> onRow,
                             BiConsumer<Long, List<FieldError>> onError) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) {
            // Empty file: zero rows
            return 0;
        }
        // We read the header but do not validate column names (positional mapping is fixed).

        long rowNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            rowNumber++;
            if (line.isBlank()) {
                continue; // skip blank trailing lines
            }
            ParsedLine pl = parseLine(rowNumber, line);
            onRow.accept(pl.row());
            if (!pl.errors().isEmpty()) {
                onError.accept(rowNumber, pl.errors());
            }
        }
        return rowNumber;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private record ParsedLine(CsvRow row, List<FieldError> errors) {}

    /**
     * Parse one CSV line. Uses a simple split that handles quoted fields with commas
     * via a minimal state machine (no external library).
     */
    private static ParsedLine parseLine(long rowNumber, String line) {
        String[] cols = splitCsv(line);
        if (cols.length != EXPECTED_COLUMNS) {
            return new ParsedLine(
                    CsvRow.invalid(rowNumber, null),
                    List.of(new FieldError("row", "WRONG_COLUMN_COUNT")));
        }

        List<FieldError> errors = new ArrayList<>();

        // Column 0: employeeId
        String employeeId = trimOrNull(cols[0]);
        if (employeeId == null) {
            errors.add(new FieldError("employeeId", "REQUIRED"));
        } else if (!ID_PATTERN.matcher(employeeId).matches()) {
            errors.add(new FieldError("employeeId", "INVALID_FORMAT"));
            employeeId = null; // not usable
        }

        // Column 1: sourceVersion
        Long sourceVersion = null;
        String rawVersion = trimOrNull(cols[1]);
        if (rawVersion == null) {
            errors.add(new FieldError("sourceVersion", "REQUIRED"));
        } else {
            try {
                sourceVersion = Long.parseLong(rawVersion);
                if (sourceVersion < 0) {
                    errors.add(new FieldError("sourceVersion", "INVALID_FORMAT"));
                    sourceVersion = null;
                }
            } catch (NumberFormatException e) {
                errors.add(new FieldError("sourceVersion", "INVALID_FORMAT"));
            }
        }

        // Column 2: sourceUpdatedAt (ISO-8601)
        Instant sourceUpdatedAt = null;
        String rawUpdatedAt = trimOrNull(cols[2]);
        if (rawUpdatedAt == null) {
            errors.add(new FieldError("sourceUpdatedAt", "REQUIRED"));
        } else {
            try {
                sourceUpdatedAt = Instant.parse(rawUpdatedAt);
            } catch (DateTimeParseException e) {
                errors.add(new FieldError("sourceUpdatedAt", "INVALID_FORMAT"));
            }
        }

        // Columns 3-8: HR profile fields
        String name     = trimOrNull(cols[3]);
        String email    = trimOrNull(cols[4]);
        String phone    = trimOrNull(cols[5]);
        String managerId  = trimOrNull(cols[6]);
        String officeCode = trimOrNull(cols[7]);
        String shiftCode  = trimOrNull(cols[8]);

        if (name == null)       errors.add(new FieldError("name",     "REQUIRED"));
        if (name != null && name.length() > 200) {
            errors.add(new FieldError("name", "TOO_LONG"));
            name = null;
        }
        if (email == null) {
            errors.add(new FieldError("email", "REQUIRED"));
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            errors.add(new FieldError("email", "INVALID_FORMAT"));
        }
        if (phone == null) {
            errors.add(new FieldError("phone", "REQUIRED"));
        } else {
            String normalized = phone.replace(" ", "").replace("-", "");
            if (!PHONE_PATTERN.matcher(normalized).matches()) {
                errors.add(new FieldError("phone", "INVALID_PHONE"));
            }
        }
        if (officeCode == null) errors.add(new FieldError("officeCode", "REQUIRED"));

        if (!errors.isEmpty()) {
            // Return the row with whatever employeeId we managed to parse.
            return new ParsedLine(CsvRow.invalid(rowNumber, employeeId), errors);
        }

        HrProfile profile = new HrProfile(name, email, phone, managerId, officeCode, shiftCode);
        CsvRow valid = CsvRow.valid(rowNumber, employeeId, sourceVersion, sourceUpdatedAt, profile);
        return new ParsedLine(valid, List.of());
    }

    /**
     * Minimal CSV splitter that handles double-quoted fields (RFC 4180 subset).
     * Does not support escaped quotes inside quoted fields; a quote inside a value
     * should use "" (doubled) which this handles.
     */
    static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"'); // escaped quote
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}

