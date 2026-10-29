package com.rosterguard.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for the transactional outbox relay:
 *
 * <ul>
 *   <li>Delivery guarantee: <strong>At least once</strong> (never lost, downstream must dedup by ID)</li>
 *   <li>Locking strategy: {@code FOR UPDATE SKIP LOCKED} prevents concurrent workers from double-claiming</li>
 *   <li>Retry policy: Exponential backoff with attempt counters</li>
 *   <li>Dead-lettering: Status transitions to {@code FAILED} after configured max attempts</li>
 * </ul>
 */
class OutboxRelayIT extends PostgresIntegrationTest {

    @Autowired
    OutboxRepository repo;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxProperties props;

    private void seedEmployee(String tenantId, String empId) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, phone, office_code, status,
                     source_version, source_updated_at, roster_hash, payload_hash)
                VALUES (?, ?, 'Name', 'email@example.com', '+919000000001', 'BLR', 'INACTIVE',
                        1, now(), repeat('a', 64), repeat('b', 64))
                ON CONFLICT (tenant_id, employee_id) DO NOTHING
                """, tenantId, empId);
    }

    private void insertPendingEvent(String tenantId, String empId, long version) {
        seedEmployee(tenantId, empId);
        repo.insertDeactivationEvent(tenantId, empId, version);
    }

    // ── 1. Happy path: publish success marks event PUBLISHED ──────────────────

    @Test
    void relay_publishesPendingEvent_andMarksPublished() {
        insertPendingEvent(T1, "RELAY-1", 10L);

        List<OutboxEvent> published = new ArrayList<>();
        EventPublisher mockPublisher = published::add;

        OutboxRelay relay = new OutboxRelay(repo, mockPublisher, tx, props);
        relay.relay();

        assertThat(published).hasSize(1);
        OutboxEvent event = published.get(0);
        assertThat(event.aggregateId()).isEqualTo("RELAY-1");

        OutboxEvent stored = repo.find(T1, event.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo("PUBLISHED");
        assertThat(stored.publishedAt()).isNotNull();
        assertThat(stored.lastError()).isNull();
    }

    // ── 2. Failed publish: retries with exponential backoff ───────────────────

    @Test
    void relay_failedPublish_retriesWithoutLosingEvent() {
        insertPendingEvent(T1, "RELAY-FAIL", 20L);

        EventPublisher failingPublisher = event -> {
            throw new RuntimeException("Downstream transport temporarily unreachable");
        };

        OutboxRelay relay = new OutboxRelay(repo, failingPublisher, tx, props);
        relay.relay();

        List<OutboxEvent> events = repo.list(T1, null, 10, null);
        assertThat(events).hasSize(1);

        OutboxEvent event = events.get(0);
        assertThat(event.status()).isEqualTo("PENDING"); // Still pending for retry!
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.lastError()).isEqualTo("RuntimeException");
        assertThat(event.nextAttemptAt()).isAfter(Instant.now()); // Backed off into future
    }

    // ── 3. Dead-lettering: event marked FAILED after max attempts ─────────────

    @Test
    void relay_exceedingMaxAttempts_transitionsToDeadLetterFailed() {
        insertPendingEvent(T1, "RELAY-DLQ", 30L);

        // Max attempts is configured as 3 in test application.yml
        assertThat(props.maxAttempts()).isEqualTo(3);

        EventPublisher failingPublisher = event -> {
            throw new RuntimeException("Permanent downstream failure");
        };

        OutboxRelay relay = new OutboxRelay(repo, failingPublisher, tx, props);

        // Attempt 1
        relay.relay();
        OutboxEvent e1 = repo.list(T1, null, 10, null).get(0);
        assertThat(e1.status()).isEqualTo("PENDING");
        assertThat(e1.attempts()).isEqualTo(1);

        // Reset next_attempt_at to now so relay can claim it for attempt 2
        jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE id = ?", e1.id());
        relay.relay();
        OutboxEvent e2 = repo.list(T1, null, 10, null).get(0);
        assertThat(e2.status()).isEqualTo("PENDING");
        assertThat(e2.attempts()).isEqualTo(2);

        // Reset next_attempt_at to now for attempt 3 (reaches maxAttempts = 3 -> FAILED)
        jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE id = ?", e2.id());
        relay.relay();
        OutboxEvent e3 = repo.list(T1, null, 10, null).get(0);
        assertThat(e3.status()).isEqualTo("FAILED");
        assertThat(e3.attempts()).isEqualTo(3);
        assertThat(e3.lastError()).isEqualTo("RuntimeException");

        // FAILED events are dead-lettered and never claimed by claimPending again
        List<OutboxEvent> claimed = tx.execute(s -> repo.claimPending());
        assertThat(claimed).isEmpty();
    }

    // ── 4. Concurrent workers: FOR UPDATE SKIP LOCKED prevents double claims ──

    @Test
    void concurrentRelayWorkers_doNotClaimSameEventSimultaneously() throws Exception {
        insertPendingEvent(T1, "CONCUR-1", 1L);
        insertPendingEvent(T1, "CONCUR-2", 2L);

        CountDownLatch worker1Claimed = new CountDownLatch(1);
        CountDownLatch worker2Done = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // Worker 1: claims rows inside a transaction, signals worker 2, then waits before committing
        Future<List<UUID>> f1 = pool.submit(() -> tx.execute(status -> {
            List<OutboxEvent> claimed = repo.claimPending();
            List<UUID> ids = claimed.stream().map(OutboxEvent::id).toList();
            worker1Claimed.countDown();
            try {
                worker2Done.await(); // Hold lock until worker 2 finishes its query
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ids;
        }));

        // Worker 2: waits until Worker 1 has locked the rows, then attempts to claim
        Future<List<UUID>> f2 = pool.submit(() -> {
            try {
                worker1Claimed.await();
                return tx.execute(status -> {
                    List<OutboxEvent> claimed = repo.claimPending();
                    return claimed.stream().map(OutboxEvent::id).toList();
                });
            } finally {
                worker2Done.countDown();
            }
        });

        List<UUID> worker1Ids = f1.get();
        List<UUID> worker2Ids = f2.get();
        pool.shutdown();

        assertThat(worker1Ids).hasSize(2);
        // Worker 2 skipped the rows locked by Worker 1: zero overlap!
        assertThat(worker2Ids).isEmpty();
    }

    @Test
    void concurrentRelayWorkers_leasePreventsDoubleClaimEvenAfterClaimTxCommits() {
        insertPendingEvent(T1, "LEASE-1", 100L);

        // Worker 1 claims the row with a 60-second lease and COMMITS the transaction
        List<OutboxEvent> worker1Batch = tx.execute(s -> repo.claimPending(60));
        assertThat(worker1Batch).hasSize(1);
        UUID claimedId = worker1Batch.get(0).id();

        // Worker 1's claim transaction has committed; row lock is released in Postgres.
        // Worker 2 attempts to claim while Worker 1 is in the middle of publishing:
        List<OutboxEvent> worker2Batch = tx.execute(s -> repo.claimPending(60));

        // Worker 2 finds 0 rows because next_attempt_at was atomically leased into the future!
        assertThat(worker2Batch).isEmpty();

        // Worker 1 finishes publishing and marks published
        repo.markPublished(T1, claimedId);
        OutboxEvent finalEvent = repo.find(T1, claimedId).orElseThrow();
        assertThat(finalEvent.status()).isEqualTo("PUBLISHED");
    }

    // ── 5. At-least-once delivery: failure after publish redelivers ────────────

    @Test
    void deliveryGuarantee_isAtLeastOnce_downstreamMustDeduplicate() {
        insertPendingEvent(T1, "AT-LEAST-ONCE", 50L);

        AtomicInteger deliveryCount = new AtomicInteger();
        EventPublisher publisher = event -> deliveryCount.incrementAndGet();

        // Simulate crash right after publish: publish was called, but DB was not updated to PUBLISHED
        List<OutboxEvent> batch = tx.execute(s -> repo.claimPending());
        assertThat(batch).hasSize(1);
        publisher.publish(batch.get(0));
        assertThat(deliveryCount.get()).isEqualTo(1);

        // The row is still PENDING in the DB (process "crashed" before status update)
        OutboxEvent stored = repo.find(T1, batch.get(0).id()).orElseThrow();
        assertThat(stored.status()).isEqualTo("PENDING");

        // Simulate lease expiration after crash so the subsequent relay cycle reclaims
        jdbc.update("UPDATE outbox_event SET next_attempt_at = now() - interval '1 second' WHERE id = ?", batch.get(0).id());

        // Next relay cycle will pick up the same event and deliver it again (at least once)
        OutboxRelay relay = new OutboxRelay(repo, publisher, tx, props);
        relay.relay();

        // Downstream received it a 2nd time!
        assertThat(deliveryCount.get()).isEqualTo(2);

        // Finally marked PUBLISHED
        OutboxEvent finalState = repo.find(T1, batch.get(0).id()).orElseThrow();
        assertThat(finalState.status()).isEqualTo("PUBLISHED");
    }
}

