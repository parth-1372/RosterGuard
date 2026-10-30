package com.rosterguard.roster;

import com.rosterguard.domain.CanonicalHasher;
import com.rosterguard.domain.HrProfile;
import java.time.Instant;

/**
 * One parsed, pre-validated row from a nightly CSV file.
 *
 * <p>Both roster_hash and payload_hash are computed here so that:
 * (a) they are always consistent with the ones stored by the API-event path, and
 * (b) the service layer never has to reparse data to hash it.
 * A row without a usable employeeId is represented with employeeId = null.
 */
public record CsvRow(
        long rowNumber,
        String employeeId,   // null if absent or unparseable
        Long sourceVersion,
        Instant sourceUpdatedAt,
        HrProfile profile,
        String rosterHash,   // null if any required profile field is missing
        String payloadHash   // null if any required profile field is missing
) {
    /** True when this row has enough data to be staged for apply. */
    public boolean isValid() {
        return employeeId != null && sourceVersion != null && sourceUpdatedAt != null
                && rosterHash != null && payloadHash != null;
    }

    /**
     * Build a valid row. The caller should have already checked that all required fields
     * are present.
     */
    public static CsvRow valid(long rowNumber, String employeeId, long sourceVersion,
                               Instant sourceUpdatedAt, HrProfile profile) {
        return new CsvRow(rowNumber, employeeId, sourceVersion, sourceUpdatedAt, profile,
                CanonicalHasher.rosterHash(profile),
                CanonicalHasher.csvPayloadHash(profile));
    }

    /** Build an invalid row. employeeId may still be present even if the row is otherwise invalid. */
    public static CsvRow invalid(long rowNumber, String employeeId) {
        return new CsvRow(rowNumber, employeeId, null, null, HrProfile.EMPTY, null, null);
    }
}
