package com.rosterguard.outbox;

/**
 * Downstream transport for outbox events. Implementations must be idempotent: the relay
 * delivers <em>at least once</em>. Downstream consumers must deduplicate by event ID.
 *
 * <p>A successful call must not throw. Any exception is treated as a transient failure and
 * the event will be retried with exponential backoff.
 */
public interface EventPublisher {

    /**
     * Publish a single outbox event to the downstream system.
     *
     * @param event the event to publish; never null
     * @throws RuntimeException if the publish failed and should be retried
     */
    void publish(OutboxEvent event);
}

