package com.rosterguard.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Public API shape for one outbox event. Payload is included (it contains only internal IDs,
 * not PII). Internal fields like last_error are sanitised: only the error class name is stored.
 */
public record OutboxView(
        UUID id,
        String aggregateId,
        String eventType,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant publishedAt) {

    public static OutboxView from(OutboxEvent e) {
        return new OutboxView(
                e.id(), e.aggregateId(), e.eventType(), e.status(),
                e.attempts(), e.nextAttemptAt(), e.lastError(),
                e.createdAt(), e.publishedAt());
    }
}

