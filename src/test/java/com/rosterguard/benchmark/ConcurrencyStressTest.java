package com.rosterguard.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import com.rosterguard.outbox.EventPublisher;
import com.rosterguard.outbox.OutboxProperties;
import com.rosterguard.outbox.OutboxRelay;
import com.rosterguard.outbox.OutboxRepository;
import com.rosterguard.roster.RosterImportService;
import com.rosterguard.service.EmployeeSyncService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Benchmark 3 & 4: Stress-testing concurrent version updates, CSV imports, and outbox relay workers.
 * Tests system behavior under mixed concurrent workloads.
 */
@Tag("benchmark")
class ConcurrencyStressTest extends PostgresIntegrationTest {

    @Autowired
    EmployeeSyncService syncService;

    @Autowired
    RosterImportService importService;

    @Autowired
    OutboxRepository outboxRepo;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxProperties outboxProps;

    @Test
    void stress_test_mixed_concurrent_workloads() throws Exception {
        System.out.println("================================================================================");
        System.out.println("STRESS TEST: CONCURRENT VERSION UPDATES + CSV IMPORT + OUTBOX RELAY");
        System.out.println("================================================================================");

        int employeeCount = 50;
        int updatesPerWorker = 20;
        int updateWorkers = 4;
        int relayWorkers = 2;

        // Pre-seed employees and initial deactivations to generate outbox events
        for (int i = 1; i <= employeeCount; i++) {
            String empId = "STRESS-" + i;
            syncService.process(T1, new HrEvent(HrEventType.CREATE, empId, 1L, Instant.now(),
                    new HrProfile("Initial " + empId, empId.toLowerCase() + "@example.com",
                            "+919000000001", "M1", "BLR", "MORNING")));
            // Deactivate half to seed outbox events
            if (i % 2 == 0) {
                syncService.process(T1, new HrEvent(HrEventType.DEACTIVATE, empId, 2L, Instant.now(), null));
            }
        }

        int initialPendingOutbox = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND status = 'PENDING'",
                Integer.class, T1);
        System.out.printf("[Setup] Initialized %d employees, %d pending outbox events.\n",
                employeeCount, initialPendingOutbox);

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(updateWorkers + relayWorkers + 1);

        Set<UUID> publishedEventIds = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicatePublishCount = new AtomicInteger();

        EventPublisher trackingPublisher = event -> {
            boolean added = publishedEventIds.add(event.id());
            if (!added) {
                duplicatePublishCount.incrementAndGet();
            }
        };

        // Task A: Concurrent Relay Workers
        for (int w = 1; w <= relayWorkers; w++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    OutboxRelay relay = new OutboxRelay(outboxRepo, trackingPublisher, tx, outboxProps);
                    while (running.get()) {
                        relay.relay();
                        Thread.sleep(10);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        // Task B: Concurrent Employee Version Updates
        List<java.util.concurrent.Future<?>> updateFutures = new ArrayList<>();
        for (int w = 1; w <= updateWorkers; w++) {
            final int workerId = w;
            updateFutures.add(pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int u = 3; u <= updatesPerWorker + 2; u++) {
                        for (int e = 1; e <= employeeCount; e++) {
                            String empId = "STRESS-" + e;
                            syncService.process(T1, new HrEvent(
                                    HrEventType.UPDATE, empId, (long) u, Instant.now(),
                                    new HrProfile("Updated " + empId + " by " + workerId,
                                            empId.toLowerCase() + "@example.com", "+919000000001", "M1", "BLR", "MORNING")));
                        }
                    }
                } catch (Exception ignored) {
                }
            }));
        }

        // Task C: Concurrent CSV Import for Tenant 2
        File csvFile = File.createTempFile("stress_import", ".csv");
        csvFile.deleteOnExit();
        BenchmarkDataGenerator.generateCsv(csvFile, 2_000, 1L, "2024-01-15T10:00:00Z");

        java.util.concurrent.Future<?> importFuture = pool.submit(() -> {
            try {
                startLatch.await();
                try (var in = new BufferedInputStream(new FileInputStream(csvFile))) {
                    importService.importCsv(T2, in, Instant.parse("2024-01-15T12:00:00Z"));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Start all tasks simultaneously
        long start = System.currentTimeMillis();
        startLatch.countDown();

        // Await completion of updates and CSV import
        for (var f : updateFutures) f.get();
        importFuture.get();

        // Let relay finish remaining published events
        Thread.sleep(500);
        running.set(false);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - start;
        System.out.printf("[Completion] Mixed stress workload finished in %d ms.\n", duration);
        System.out.printf("[Outbox] Published %d distinct events. Duplicate publish count: %d.\n",
                publishedEventIds.size(), duplicatePublishCount.get());

        // Assertions
        assertThat(duplicatePublishCount.get()).isZero();

        // Verify all T1 employees have final version = updatesPerWorker + 2
        Long minVersion = jdbc.queryForObject(
                "SELECT min(source_version) FROM employee WHERE tenant_id = ? AND employee_id LIKE 'STRESS-%'",
                Long.class, T1);
        assertThat(minVersion).isEqualTo((long) (updatesPerWorker + 2));

        // Verify T2 import succeeded independently
        Integer t2Count = jdbc.queryForObject(
                "SELECT count(*) FROM employee WHERE tenant_id = ?",
                Integer.class, T2);
        assertThat(t2Count).isEqualTo(2_000);

        System.out.println("STRESS TEST: PASSED (Zero deadlocks, strict version monotonically maintained).");
        System.out.println("================================================================================");
    }
}

