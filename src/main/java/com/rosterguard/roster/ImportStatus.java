package com.rosterguard.roster;

/**
 * Lifecycle of a roster import. Matches the CHECK constraint in V2 schema.
 *
 * <pre>
 * STAGING ──► AWAITING_APPROVAL  (safety brake: threshold, watermark, duplicates)
 *   │               │
 *   │               ├── approve ──► COMPLETED
 *   │               └── reject  ──► REJECTED
 *   │
 *   ├──► NEEDS_REVIEW  (data quality issues: duplicate IDs, missing-ID rows)
 *   ├──► COMPLETED     (all checks passed, no deactivations held)
 *   └──► FAILED        (unexpected error during staging)
 * </pre>
 */
public enum ImportStatus {
    STAGING,
    COMPLETED,
    AWAITING_APPROVAL,
    NEEDS_REVIEW,
    REJECTED,
    FAILED
}

