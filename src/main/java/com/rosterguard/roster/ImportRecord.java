package com.rosterguard.roster;

import java.time.Instant;
import java.util.UUID;

/**
 * What the repository returns for a roster_import row. Read-only view for the API and service.
 */
public record ImportRecord(
        String tenantId,
        UUID id,
        ImportStatus status,
        Instant snapshotGeneratedAt,
        Integer activeCountAtStart,
        int totalRows,
        int rejectedRows,
        int newCount,
        int changedCount,
        int missingCount,
        String holdReason,
        String approvedBy,
        Instant createdAt,
        Instant completedAt
) {}

