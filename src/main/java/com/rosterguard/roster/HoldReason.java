package com.rosterguard.roster;

/**
 * Why a roster import was held rather than applied automatically.
 * Stored in roster_import.hold_reason.
 */
public enum HoldReason {
    /** At least one row in the file had no parseable employee ID. */
    MISSING_EMPLOYEE_ID,
    /** The same employee ID appeared more than once in the file. */
    DUPLICATE_EMPLOYEE_ID,
    /** The fraction of active employees absent from the file exceeds the safety threshold. */
    MISSING_THRESHOLD_EXCEEDED,
    /**
     * snapshotGeneratedAt was not supplied by the caller. Without a comparable source watermark
     * we cannot safely protect against old-file overwrites, so deactivations are blocked.
     */
    NO_SNAPSHOT_WATERMARK
}

