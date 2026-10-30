package com.rosterguard.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.HrProfile;
import com.rosterguard.repository.EmployeeRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for CSV safety gates:
 * <ul>
 *   <li>Configurable missing-employee threshold validation (0.0 to 1.0)</li>
 *   <li>Missing threshold exceeded triggers AWAITING_APPROVAL</li>
 *   <li>Stale snapshot protection against tenant.latest_snapshot_watermark</li>
 *   <li>Unknown office code captured in import_row_error</li>
 *   <li>Atomic approve/reject preventing race conditions</li>
 * </ul>
 */
class RosterImportSafetyGatesIT extends PostgresIntegrationTest {

    @Autowired
    RosterImportService importService;

    @Autowired
    RosterImportRepository importRepo;

    @Autowired
    EmployeeRepository employeeRepo;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDb() {
        jdbc.update("DELETE FROM outbox_event WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM import_row_error WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM import_seen_id WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM roster_staging WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM roster_import WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM employee WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("UPDATE tenant SET latest_snapshot_watermark = NULL WHERE id IN (?, ?)", T1, T2);
    }

    private void seedActiveEmployee(String tenantId, String empId, Instant updatedAt) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, phone, office_code, status,
                     source_version, source_updated_at, roster_hash, payload_hash)
                VALUES (?, ?, 'Name', 'email@test.com', '+919000000001', 'BLR', 'ACTIVE',
                        1, ?, repeat('a', 64), repeat('b', 64))
                """, tenantId, empId, updatedAt == null ? null : java.sql.Timestamp.from(updatedAt));
    }

    // ── 1. Threshold Configuration & Gate ────────────────────────────────────

    @Test
    void thresholdValidation_rejectsOutOfBounds() {
        assertThatThrownBy(() -> new RosterProperties(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RosterProperties(1.1))
                .isInstanceOf(IllegalArgumentException.class);

        // Valid boundaries
        assertThat(new RosterProperties(0.0).missingThreshold()).isEqualTo(0.0);
        assertThat(new RosterProperties(1.0).missingThreshold()).isEqualTo(1.0);
    }

    @Test
    void missingThresholdExceeded_holdsAsAwaitingApproval() throws Exception {
        // Seed 10 active employees
        Instant tBefore = Instant.parse("2024-01-15T08:00:00Z");
        for (int i = 1; i <= 10; i++) {
            seedActiveEmployee(T1, "E-HOLD-" + i, tBefore);
        }

        // CSV only contains 6 employees (4 are missing = 40% > 30% default threshold)
        StringBuilder sb = new StringBuilder("employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode\n");
        for (int i = 1; i <= 6; i++) {
            sb.append("E-HOLD-%d,2,2024-01-15T10:00:00Z,Name %d,n%d@test.com,+919000000001,,BLR,DAY\n".formatted(i, i, i));
        }

        UUID importId = importService.importCsv(T1,
                new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2024-01-15T12:00:00Z"));

        ImportRecord rec = importService.findImport(T1, importId).orElseThrow();
        assertThat(rec.status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);
        assertThat(rec.holdReason()).isEqualTo(HoldReason.MISSING_THRESHOLD_EXCEEDED.name());
        assertThat(rec.missingCount()).isEqualTo(4);

        // Active employees were NOT deactivated yet
        int activeCount = importRepo.countActive(T1);
        assertThat(activeCount).isEqualTo(10);
    }

    // ── 2. Stale Snapshot Protection ──────────────────────────────────────────

    @Test
    void staleSnapshotWatermark_holdsAsNeedsReview() throws Exception {
        Instant t1 = Instant.parse("2024-01-15T12:00:00Z");
        Instant tStale = Instant.parse("2024-01-15T11:00:00Z");

        // First import at t1 succeeds and establishes watermark
        String csv1 = """
                employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
                E-SNAP-1,1,2024-01-15T12:00:00Z,Snap One,snap1@test.com,+919000000001,,BLR,DAY
                """;
        UUID id1 = importService.importCsv(T1,
                new ByteArrayInputStream(csv1.getBytes(StandardCharsets.UTF_8)), t1);

        ImportRecord rec1 = importService.findImport(T1, id1).orElseThrow();
        assertThat(rec1.status()).isEqualTo(ImportStatus.COMPLETED);

        Optional<Instant> watermark = importRepo.getLatestSnapshotWatermark(T1);
        assertThat(watermark).isPresent().contains(t1);

        // Second import with tStale < t1 is held
        String csv2 = """
                employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
                E-SNAP-1,2,2024-01-15T11:00:00Z,Snap Older,snap1@test.com,+919000000001,,BLR,DAY
                """;
        UUID id2 = importService.importCsv(T1,
                new ByteArrayInputStream(csv2.getBytes(StandardCharsets.UTF_8)), tStale);

        ImportRecord rec2 = importService.findImport(T1, id2).orElseThrow();
        assertThat(rec2.status()).isEqualTo(ImportStatus.NEEDS_REVIEW);
        assertThat(rec2.holdReason()).isEqualTo(HoldReason.STALE_SNAPSHOT.name());
    }

    // ── 3. Unknown Office Reporting ───────────────────────────────────────────

    @Test
    void unknownOffice_recordedInRowError() throws Exception {
        String csv = """
                employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
                E-OFF-1,1,2024-01-15T12:00:00Z,Valid Emp,valid@test.com,+919000000001,,NON_EXISTENT_OFFICE,DAY
                """;
        UUID importId = importService.importCsv(T1,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2024-01-15T12:00:00Z"));

        List<ImportErrorRecord> errors = importRepo.listErrors(T1, importId, null, 10);
        assertThat(errors).hasSize(1);
        ImportErrorRecord err = errors.get(0);
        assertThat(err.employeeId()).isEqualTo("E-OFF-1");
        assertThat(err.field()).isEqualTo("officeCode");
        assertThat(err.code()).isEqualTo("UNKNOWN_OFFICE");
    }

    // ── 4. Atomic Approval & Rejection Race Protection ────────────────────────

    @Test
    void approveAndRejectRace_onlyOneWins() throws Exception {
        // Seed 2 active employees and submit CSV missing 1 employee (> 30% missing)
        Instant tBefore = Instant.parse("2024-01-15T08:00:00Z");
        seedActiveEmployee(T1, "E-RACE-1", tBefore);
        seedActiveEmployee(T1, "E-RACE-2", tBefore);

        String csv = """
                employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
                E-RACE-1,2,2024-01-15T10:00:00Z,Race One,race1@test.com,+919000000001,,BLR,DAY
                """;

        UUID importId = importService.importCsv(T1,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2024-01-15T12:00:00Z"));

        ImportRecord rec = importService.findImport(T1, importId).orElseThrow();
        assertThat(rec.status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);

        // First operator approves
        Optional<ImportRecord> approved = importService.approve(T1, importId, "op-1");
        assertThat(approved).isPresent();
        assertThat(approved.get().status()).isEqualTo(ImportStatus.COMPLETED);

        // Second operator attempts to reject or approve afterwards -> must return empty!
        Optional<ImportRecord> rejectedSecond = importService.reject(T1, importId, "op-2");
        assertThat(rejectedSecond).isEmpty();

        Optional<ImportRecord> approvedSecond = importService.approve(T1, importId, "op-2");
        assertThat(approvedSecond).isEmpty();
    }
}

