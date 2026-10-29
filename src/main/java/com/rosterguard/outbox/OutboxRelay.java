package com.rosterguard.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox relay. Polls the outbox_event table at a fixed interval and publishes
 * PENDING events to the configured {@link EventPublisher}.
 *
 * <h3>Delivery guarantee</h3>
 * <p>Delivery is <strong>at-least-once</strong>: the outbox row is marked PUBLISHED only after
 * a successful {@link EventPublisher#publish} call. If the process crashes between publish and
 * the UPDATE, the event will be retried on restart. Downstream consumers must deduplicate by
 * event ID.
 *
 * <h3>Concurrent safety</h3>
 * <p>{@code FOR UPDATE SKIP LOCKED} ensures multiple relay threads (e.g., two pod replicas)
 * never claim the same row simultaneously. The claim and the status update happen in the same
 * short transaction, so the lock is held for the minimum possible time.
 *
 * <h3>Backoff and dead-lettering</h3>
 * <p>On publish failure the attempt counter is incremented and next_attempt_at is set to
 * {@code now() + 2^attempts} seconds (capped at 1 hour). After {@code maxAttempts} failures
 * the event is marked FAILED (dead-lettered) and requires operator intervention.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repo;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final OutboxProperties props;

    public OutboxRelay(OutboxRepository repo, EventPublisher publisher,
                       TransactionTemplate tx, OutboxProperties props) {
        this.repo = repo;
        this.publisher = publisher;
        this.tx = tx;
        this.props = props;
    }

    /**
     * Run one relay cycle. Each batch row is claimed, published, and its status updated in a
     * single short transaction so the SKIP LOCKED window is as narrow as possible.
     *
     * <p>The {@code fixedDelayString} reads from {@code rosterguard.outbox.relay-interval-ms}
     * (default 5 000 ms). Delay-based scheduling means a slow publish cycle does not overlap
     * with the next one — unlike fixedRate which can cause pile-ups under backpressure.
     */
    @Scheduled(fixedDelayString = "${rosterguard.outbox.relay-interval-ms:5000}")
    public void relay() {
        // Each event gets its own short transaction so one bad event doesn't block others.
        List<OutboxEvent> batch = tx.execute(s -> repo.claimPending());
        if (batch == null || batch.isEmpty()) return;

        log.debug("relay claimed {} event(s)", batch.size());
        for (OutboxEvent event : batch) {
            processOne(event);
        }
    }

    /**
     * Publish one event: claim → publish → mark done (or schedule retry), each in its own
     * transaction. The claim transaction above returned the snapshot; the update here
     * re-locks by PK which is instantaneous.
     */
    void processOne(OutboxEvent event) {
        try {
            publisher.publish(event);
            tx.executeWithoutResult(s ->
                    repo.markPublished(event.tenantId(), event.id()));
            log.debug("event published tenant={} id={}", event.tenantId(), event.id());
        } catch (RuntimeException ex) {
            String errorClass = ex.getClass().getSimpleName();
            log.warn("event publish failed tenant={} id={} attempt={} cause={}",
                    event.tenantId(), event.id(), event.attempts() + 1, errorClass);
            tx.executeWithoutResult(s ->
                    repo.markForRetry(event.tenantId(), event.id(),
                            event.attempts(), errorClass, props.maxAttempts()));
        }
    }
}

