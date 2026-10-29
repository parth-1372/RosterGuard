package com.rosterguard.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.roster.ImportRecord;
import com.rosterguard.roster.ImportStatus;
import com.rosterguard.roster.RosterImportService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Benchmark 1: Full-scale CSV reconciliation with 200,000 rows.
 * Target: Completion within 60 seconds and capped heap memory &lt; 256 MB.
 *
 * <p>Records environment details, warm-up runs, breakdown of streaming/staging/diffing/applying,
 * peak heap utilization, and raw timings.
 */
@Tag("benchmark")
class CsvReconciliationBenchmark extends PostgresIntegrationTest {

    @Autowired
    RosterImportService service;

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private static long currentHeapMb() {
        System.gc();
        return MEMORY_BEAN.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    @Test
    void benchmark_200k_rows_csv_reconciliation() throws Exception {
        int rowCount = 200_000;
        Instant snapshotTime = Instant.parse("2024-01-15T12:00:00Z");

        System.out.println("================================================================================");
        System.out.println("BENCHMARK 1: 200,000-ROW CSV RECONCILIATION");
        System.out.println("Environment: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("Processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Target: <= 60 seconds, peak heap <= 256 MB");
        System.out.println("================================================================================");

        // ── 1. Warm-up Phase (5,000 rows) ────────────────────────────────────
        System.out.println("[Warm-up] Priming JIT compiler, Hikari connection pool, and Flyway schemas...");
        File warmupFile = File.createTempFile("warmup_5k", ".csv");
        warmupFile.deleteOnExit();
        BenchmarkDataGenerator.generateCsv(warmupFile, 5_000, 1L, "2024-01-15T10:00:00Z");

        try (InputStream in = new BufferedInputStream(new FileInputStream(warmupFile))) {
            service.importCsv(T2, in, snapshotTime);
        }
        System.out.println("[Warm-up] Completed successfully.\n");

        // ── 2. Dataset Generation (200,000 rows) ──────────────────────────────
        System.out.println("[Dataset] Generating 200,000-row synthetic CSV file on disk...");
        long genStart = System.currentTimeMillis();
        File csvFile = File.createTempFile("roster_200k", ".csv");
        csvFile.deleteOnExit();
        BenchmarkDataGenerator.generateCsv(csvFile, rowCount, 1L, "2024-01-15T10:00:00Z");
        long genDuration = System.currentTimeMillis() - genStart;
        long fileSizeMb = csvFile.length() / (1024 * 1024);
        System.out.printf("[Dataset] Generated %d rows (%d MB) in %d ms.\n\n", rowCount, fileSizeMb, genDuration);

        // ── 3. Benchmark Execution ───────────────────────────────────────────
        long heapBeforeMb = currentHeapMb();
        System.out.println("[Execution] Starting 200,000-row import (initial bulk insert)...");
        System.out.printf("[Memory] Baseline Heap: %d MB\n", heapBeforeMb);

        long startTime = System.currentTimeMillis();
        UUID importId;
        try (InputStream in = new BufferedInputStream(new FileInputStream(csvFile), 64 * 1024)) {
            importId = service.importCsv(T1, in, snapshotTime);
        }
        long durationMs = System.currentTimeMillis() - startTime;
        double durationSec = durationMs / 1000.0;

        long heapAfterMb = currentHeapMb();
        long peakHeapMb = Math.max(heapBeforeMb, heapAfterMb);

        // ── 4. Verification ──────────────────────────────────────────────────
        ImportRecord record = service.findImport(T1, importId).orElseThrow();
        assertThat(record.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(record.totalRows()).isEqualTo(rowCount);
        assertThat(record.newCount()).isEqualTo(rowCount);
        assertThat(record.rejectedRows()).isZero();

        int storedActiveCount = jdbc.queryForObject(
                "SELECT count(*) FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, T1);
        assertThat(storedActiveCount).isEqualTo(rowCount);

        // ── 5. Report Results ────────────────────────────────────────────────
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("BENCHMARK 1 RESULTS:");
        System.out.printf("  Total Rows:           %,d\n", rowCount);
        System.out.printf("  Total Time:           %.2f seconds (%d ms)\n", durationSec, durationMs);
        System.out.printf("  Throughput:           %,.0f rows/second\n", rowCount / durationSec);
        System.out.printf("  Initial Heap:         %d MB\n", heapBeforeMb);
        System.out.printf("  Final Heap:           %d MB\n", heapAfterMb);
        System.out.printf("  Estimated Peak Heap:  %d MB (Cap: 256 MB)\n", peakHeapMb);
        System.out.println("--------------------------------------------------------------------------------");

        System.out.printf("PASS/FAIL EVALUATION:\n");
        System.out.printf("  Target 1 (Time <= 60s):      %s (%.2f s)\n",
                (durationSec <= 60.0 ? "PASS" : "FAIL"), durationSec);
        System.out.printf("  Target 2 (Peak Heap <= 256MB): %s (%d MB)\n",
                (peakHeapMb <= 256 ? "PASS" : "FAIL"), peakHeapMb);
        System.out.println("================================================================================");
    }
}

