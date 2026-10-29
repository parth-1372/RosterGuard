package com.rosterguard.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of one row in outbox_event, returned from the repository for the relay
 * and for the monitoring API. No PII: payload is opaque JSONB, the relay does not inspect it.
 */
public record OutboxEvent(
        String tenantId,
        UUID id,
        String aggregateId,
        String eventType,
        String dedupKey,
        String payload,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant publishedAt) {
}

