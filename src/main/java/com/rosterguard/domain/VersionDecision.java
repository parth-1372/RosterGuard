package com.rosterguard.domain;

/** What to do with an incoming versioned change, given what is already stored. */
public enum VersionDecision {
    /** Incoming is newer (or the employee is new): apply it. */
    APPLY,
    /** Same version, same payload: already processed. No-op. */
    DUPLICATE,
    /** Incoming is older than stored: ignore. */
    STALE,
    /** Same version, different payload: ambiguous. Hold for review, change nothing. */
    CONFLICT
}
