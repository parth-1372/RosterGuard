package com.rosterguard.domain;

/**
 * The single version-comparison rule, shared by API events and (later) CSV rows.
 *
 * <p>Versions are source-supplied per-employee integers; larger means newer. We never compare
 * timestamps for ordering. The database enforces the same "incoming &gt; stored" condition
 * atomically; this class classifies the outcome when the write did not apply.
 */
public final class VersionPolicy {

    private VersionPolicy() {
    }

    public static VersionDecision decide(Long storedVersion, String storedPayloadHash,
                                         long incomingVersion, String incomingPayloadHash) {
        if (storedVersion == null || incomingVersion > storedVersion) {
            return VersionDecision.APPLY;
        }
        if (incomingVersion < storedVersion) {
            return VersionDecision.STALE;
        }
        return storedPayloadHash.equals(incomingPayloadHash)
                ? VersionDecision.DUPLICATE
                : VersionDecision.CONFLICT;
    }
}
