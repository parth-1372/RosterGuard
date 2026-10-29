package com.rosterguard.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import com.rosterguard.service.EmployeeSyncService;
import com.rosterguard.service.EventResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Benchmark 2: Event-ingestion throughput and p50/p90/p95/p99 latency under concurrency.
 * Tests single-event ingestion across multiple concurrency tiers (1, 4, 8, 16 threads),
 * as well as batch ingestion throughput.
 */
@Tag("benchmark")
class EventIngestionBenchmark extends PostgresIntegrationTest {

    @Autowired
    EmployeeSyncService service;

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private static HrEvent createEvent(String id, long version) {
        return new HrEvent(
                HrEventType.CREATE, id, version, T0,
                new HrProfile("Employee " + id, id.toLowerCase() + "@example.com",
                        "+919000000001", "M1", "BLR", "MORNING"));
    }

    @Test
    void benchmark_event_ingestion_throughput_and_latency() throws Exception {
        System.out.println("================================================================================");
        System.out.println("BENCHMARK 2: HR EVENT INGESTION THROUGHPUT & LATENCY (p50 / p90 / p95 / p99)");
        System.out.println("Environment: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Available Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("================================================================================");

        // Warm-up
        for (int i = 1; i <= 200; i++) {
            service.process(T2, createEvent("WARM-" + i, 1L));
        }

        int totalEventsPerTier = 2_000;
        int[] concurrencyTiers = {1, 4, 8, 16};

        for (int threads : concurrencyTiers) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(totalEventsPerTier));

            long tierStart = System.currentTimeMillis();
            List<Callable<Void>> tasks = new ArrayList<>();

            for (int i = 1; i <= totalEventsPerTier; i++) {
                final String empId = String.format("E-%dT-%d", threads, i);
                final HrEvent event = createEvent(empId, 1L);
                tasks.add(() -> {
                    long s = System.nanoTime();
                    EventResult res = service.process(T1, event);
                    long elapsed = System.nanoTime() - s;
                    latenciesNanos.add(elapsed);
                    assertThat(res.outcome()).isEqualTo(EventResult.Outcome.APPLIED);
                    return null;
                });
            }

            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) f.get();
            long tierDurationMs = System.currentTimeMillis() - tierStart;
            pool.shutdown();

            Collections.sort(latenciesNanos);
            double throughput = (totalEventsPerTier * 1000.0) / tierDurationMs;
            double p50Ms = latenciesNanos.get((int) (totalEventsPerTier * 0.50)) / 1_000_000.0;
            double p90Ms = latenciesNanos.get((int) (totalEventsPerTier * 0.90)) / 1_000_000.0;
            double p95Ms = latenciesNanos.get((int) (totalEventsPerTier * 0.95)) / 1_000_000.0;
            double p99Ms = latenciesNanos.get((int) (totalEventsPerTier * 0.99)) / 1_000_000.0;

            System.out.printf("Concurrency: %2d threads | Events: %,d | Total Time: %4d ms | Throughput: %,6.0f ops/sec\n",
                    threads, totalEventsPerTier, tierDurationMs, throughput);
            System.out.printf("  Latency -> p50: %5.2f ms | p90: %5.2f ms | p95: %5.2f ms | p99: %5.2f ms\n",
                    p50Ms, p90Ms, p95Ms, p99Ms);
            System.out.println("--------------------------------------------------------------------------------");
        }

        // ── Batch Ingestion Benchmark ─────────────────────────────────────────
        System.out.println("\n[Batch Ingestion] Testing batch processing of 1,000 events in batches of 100...");
        List<HrEvent> batch = new ArrayList<>();
        for (int i = 1; i <= 1_000; i++) {
            batch.add(createEvent("BATCH-" + i, 1L));
        }

        long batchStart = System.currentTimeMillis();
        List<EventResult> batchResults = service.processBatch(T1, batch);
        long batchDurationMs = System.currentTimeMillis() - batchStart;

        assertThat(batchResults).hasSize(1_000);
        double batchThroughput = (1_000.0 * 1000.0) / batchDurationMs;
        System.out.printf("Batch Size: 1,000 | Total Time: %d ms | Throughput: %,.0f ops/sec\n",
                batchDurationMs, batchThroughput);
        System.out.println("================================================================================");
    }
}

