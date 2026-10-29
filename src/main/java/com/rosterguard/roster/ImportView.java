package com.rosterguard.roster;

import java.time.Instant;
import java.util.UUID;

/**
 * What the API returns for a roster import. Mirrors ImportRecord but is a stable
 * public contract — internal fields like tenant_id are omitted.
 */
public record ImportView(
        String id,
        String status,
        Instant snapshotGeneratedAt,
        Integer activeCountAtStart,
        int totalRows,
        int rejectedRows,
        int newCount,
        int changedCount,
        int missingCount,
        String holdReason,
        Instant createdAt,
        Instant completedAt
) {
    public static ImportView from(ImportRecord r) {
        return new ImportView(
                r.id().toString(), r.status().name(), r.snapshotGeneratedAt(),
                r.activeCountAtStart(), r.totalRows(), r.rejectedRows(),
                r.newCount(), r.changedCount(), r.missingCount(),
                r.holdReason(), r.createdAt(), r.completedAt());
    }
}
