package com.rosterguard.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.roster.HoldReason;
import com.rosterguard.roster.ImportRecord;
import com.rosterguard.roster.ImportStatus;
import com.rosterguard.roster.RosterImportService;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Benchmark 5: Memory usage and resilience under large files with corrupted data:
 * malformed rows, duplicate employee IDs, and missing ID rows.
 */
@Tag("benchmark")
class LargeFileResilienceTest extends PostgresIntegrationTest {

    @Autowired
    RosterImportService service;

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    private static long currentHeapMb() {
        System.gc();
        return MEMORY_BEAN.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    @Test
    void resilience_largeFile_withMalformedRows_maintainsO1Memory() throws Exception {
        int totalRows = 20_000;
        File file = File.createTempFile("corrupted_20k", ".csv");
        file.deleteOnExit();

        // 10% malformed rows, 0 duplicate IDs
        BenchmarkDataGenerator.generateCorruptedCsv(file, totalRows, 0.10, 0.0);

        long heapStart = currentHeapMb();
        long start = System.currentTimeMillis();

        UUID importId;
        try (var in = new BufferedInputStream(new FileInputStream(file))) {
            importId = service.importCsv(T1, in, Instant.parse("2024-01-15T12:00:00Z"));
        }
        long duration = System.currentTimeMillis() - start;
        long heapEnd = currentHeapMb();

        ImportRecord record = service.findImport(T1, importId).orElseThrow();
        assertThat(record.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(record.totalRows()).isEqualTo(totalRows);
        assertThat(record.rejectedRows()).isEqualTo(2_000); // 10% of 20,000 = 2,000 malformed
        assertThat(record.newCount()).isEqualTo(18_000);   // 18,000 valid rows staged and upserted

        // Verify heap difference is minimal (O(1) streaming)
        long heapDeltaMb = Math.abs(heapEnd - heapStart);
        System.out.printf("[Resilience] 20k rows (10%% malformed) processed in %d ms. Heap Delta: %d MB.\n",
                duration, heapDeltaMb);
        assertThat(heapDeltaMb).isLessThan(50); // Well under 50 MB variance
    }

    @Test
    void resilience_duplicateIds_abortsDestructiveReconciliation() throws Exception {
        int totalRows = 5_000;
        File file = File.createTempFile("duplicates_5k", ".csv");
        file.deleteOnExit();

        // Introduce 100 duplicate IDs
        BenchmarkDataGenerator.generateCorruptedCsv(file, totalRows, 0.0, 0.02);

        UUID importId;
        try (var in = new BufferedInputStream(new FileInputStream(file))) {
            importId = service.importCsv(T1, in, Instant.parse("2024-01-15T12:00:00Z"));
        }

        ImportRecord record = service.findImport(T1, importId).orElseThrow();
        // Safety Gate B must trigger NEEDS_REVIEW
        assertThat(record.status()).isEqualTo(ImportStatus.NEEDS_REVIEW);
        assertThat(record.holdReason()).isEqualTo(HoldReason.DUPLICATE_EMPLOYEE_ID.name());

        // Zero destructive writes or upserts applied
        assertThat(record.newCount()).isZero();
        int employeeTableCount = jdbc.queryForObject(
                "SELECT count(*) FROM employee WHERE tenant_id = ?",
                Integer.class, T1);
        assertThat(employeeTableCount).isZero();
    }
}

