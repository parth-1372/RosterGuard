package com.rosterguard.roster;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for the roster import pipeline using a real PostgreSQL container.
 * Each test runs against an isolated set of tables (PostgresIntegrationTest resets data
 * before every test), so tests are independent.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Happy-path: new employees created, changed employees updated, missing deactivated</li>
 *   <li>Old-file protection: employee newer than snapshot is not deactivated</li>
 *   <li>Threshold safety brake: &ge;30% missing → AWAITING_APPROVAL</li>
 *   <li>Approve and reject a held import</li>
 *   <li>Duplicate employee ID → NEEDS_REVIEW, no destructive apply</li>
 *   <li>Row with no employee ID → NEEDS_REVIEW</li>
 *   <li>Malformed rows do not prevent valid rows from being staged</li>
 *   <li>No snapshot watermark → AWAITING_APPROVAL, no deactivations</li>
 *   <li>Tenant isolation: T2 import does not touch T1 employees</li>
 *   <li>Stale version: CSV row with older version does not overwrite newer employee</li>
 *   <li>Large-file simulation: 1000-row batch processes without error</li>
 * </ul>
 */
class RosterImportServiceIT extends PostgresIntegrationTest {

    @Autowired
    RosterImportService service;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final String HEADER =
            "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode";

    private static final Instant SNAPSHOT = Instant.parse("2024-01-15T12:00:00Z");
    private static final Instant BEFORE_SNAPSHOT = Instant.parse("2024-01-15T10:00:00Z");
    private static final Instant AFTER_SNAPSHOT  = Instant.parse("2024-01-15T14:00:00Z");

    private String row(String id, long version, Instant updatedAt) {
        return id + "," + version + "," + updatedAt + ",Employee " + id
                + ",emp" + id.toLowerCase() + "@example.com,+919000000001,,BLR,MORNING";
    }

    private UUID doImport(String tenantId, String csvBody, Instant snapshotAt) throws IOException {
        String csv = HEADER + "\n" + csvBody;
        return service.importCsv(tenantId, toStream(csv), snapshotAt);
    }

    private static ByteArrayInputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private ImportRecord fetch(String tenantId, UUID id) {
        return service.findImport(tenantId, id).orElseThrow();
    }

    /** Insert an employee directly at the repository level, bypassing the sync service. */
    private void seedEmployee(String tenantId, String employeeId, long version,
                              Instant sourceUpdatedAt) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, phone, source_version,
                     source_updated_at, roster_hash, payload_hash, status)
                VALUES (?, ?, ?, ?, ?, ?, ?,
                        repeat('a', 64), repeat('b', 64), 'ACTIVE')
                """,
                tenantId, employeeId, "Seeded " + employeeId,
                "seeded." + employeeId.toLowerCase() + "@example.com",
                "+919000000099", version, toDbTs(sourceUpdatedAt));
    }

    private static java.time.OffsetDateTime toDbTs(Instant i) {
        return i == null ? null : java.time.OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    private String statusOf(String tenantId, String employeeId) {
        return jdbc.queryForObject(
                "SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?",
                String.class, tenantId, employeeId);
    }

    private long versionOf(String tenantId, String employeeId) {
        return jdbc.queryForObject(
                "SELECT source_version FROM employee WHERE tenant_id = ? AND employee_id = ?",
                Long.class, tenantId, employeeId);
    }

    // ── 1. Happy path ─────────────────────────────────────────────────────────

    @Test
    void happyPath_newEmployeesCreated() throws IOException {
        String csv = row("E001", 1, BEFORE_SNAPSHOT) + "\n"
                   + row("E002", 1, BEFORE_SNAPSHOT);
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(r.newCount()).isEqualTo(2);
        assertThat(r.changedCount()).isEqualTo(0);
        assertThat(r.missingCount()).isEqualTo(0);
        assertThat(r.totalRows()).isEqualTo(2);
        assertThat(r.rejectedRows()).isEqualTo(0);

        assertThat(statusOf(T1, "E001")).isEqualTo("ACTIVE");
        assertThat(statusOf(T1, "E002")).isEqualTo("ACTIVE");
    }

    @Test
    void happyPath_missingActiveEmployeeDeactivated() throws IOException {
        // Seed 4 employees so that 1 missing / 4 = 25% < 30% threshold.
        seedEmployee(T1, "E001", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E002", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E003", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E004", 1, BEFORE_SNAPSHOT);

        // Import contains E001, E002, E003 → E004 is missing (1/4 = 25%).
        String csv = row("E001", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E002", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E003", 2, BEFORE_SNAPSHOT);
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(r.missingCount()).isEqualTo(1);
        assertThat(statusOf(T1, "E004")).isEqualTo("INACTIVE");
        assertThat(statusOf(T1, "E001")).isEqualTo("ACTIVE");
    }

    @Test
    void happyPath_changedEmployeeUpdated() throws IOException {
        seedEmployee(T1, "E001", 1, BEFORE_SNAPSHOT);

        // Same employee with a newer version → should be updated (CHANGED).
        UUID id = doImport(T1, row("E001", 2, BEFORE_SNAPSHOT), SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        // New count 0 (employee exists); changed count 1 (profile differs due to name change).
        assertThat(r.newCount()).isEqualTo(0);
        assertThat(versionOf(T1, "E001")).isEqualTo(2L);
    }

    // ── 2. Old-file protection ────────────────────────────────────────────────

    @Test
    void oldFileProtection_employeeNewerThanSnapshot_notDeactivated() throws IOException {
        // E001 has source_updated_at AFTER the snapshot — HR has more recent info.
        seedEmployee(T1, "E001", 5, AFTER_SNAPSHOT);
        // Seed 4 more employees so 1 missing / 5 total = 20% < threshold.
        seedEmployee(T1, "E002", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E003", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E004", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E005", 1, BEFORE_SNAPSHOT);

        // E002-E005 in file; E001 is absent but its source_updated_at > snapshotGeneratedAt.
        String csv = row("E002", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E003", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E004", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E005", 2, BEFORE_SNAPSHOT);
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        // E001 should NOT be deactivated — its source_updated_at > snapshotGeneratedAt.
        assertThat(statusOf(T1, "E001")).isEqualTo("ACTIVE");
        // Missing count is 1 (E001 absent) but deactivation was skipped for it.
        assertThat(r.missingCount()).isEqualTo(1);
    }

    // ── 3. No snapshot watermark ──────────────────────────────────────────────

    @Test
    void noWatermark_upsertApplied_deactivationsHeld() throws IOException {
        seedEmployee(T1, "E001", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E002", 1, BEFORE_SNAPSHOT);

        // Import with null snapshot: E001 updated, E002 missing — but not deactivated.
        UUID id = doImport(T1, row("E001", 2, BEFORE_SNAPSHOT), null /* no watermark */);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);
        assertThat(r.holdReason()).isEqualTo(HoldReason.NO_SNAPSHOT_WATERMARK.name());
        // Upserts are applied even when held.
        assertThat(versionOf(T1, "E001")).isEqualTo(2L);
        // Deactivations are NOT applied.
        assertThat(statusOf(T1, "E002")).isEqualTo("ACTIVE");
    }

    // ── 4. Missing threshold ──────────────────────────────────────────────────

    @Test
    void threshold_30percentMissing_awaitingApproval() throws IOException {
        // Seed 10 active employees.
        for (int i = 1; i <= 10; i++) {
            seedEmployee(T1, "E" + String.format("%03d", i), 1, BEFORE_SNAPSHOT);
        }
        // Import only 7 of them → 3/10 = 30% missing = threshold exactly.
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            csv.append(row("E" + String.format("%03d", i), 2, BEFORE_SNAPSHOT)).append("\n");
        }
        UUID id = doImport(T1, csv.toString().strip(), SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);
        assertThat(r.holdReason()).isEqualTo(HoldReason.MISSING_THRESHOLD_EXCEEDED.name());
        assertThat(r.missingCount()).isEqualTo(3);
        // Missing employees NOT yet deactivated.
        assertThat(statusOf(T1, "E008")).isEqualTo("ACTIVE");
    }

    @Test
    void threshold_below30percent_completesAutomatically() throws IOException {
        // 10 active; 8 in file → 2/10 = 20% < threshold.
        for (int i = 1; i <= 10; i++) {
            seedEmployee(T1, "E" + String.format("%03d", i), 1, BEFORE_SNAPSHOT);
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            csv.append(row("E" + String.format("%03d", i), 2, BEFORE_SNAPSHOT)).append("\n");
        }
        UUID id = doImport(T1, csv.toString().strip(), SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(statusOf(T1, "E009")).isEqualTo("INACTIVE");
        assertThat(statusOf(T1, "E010")).isEqualTo("INACTIVE");
    }

    // ── 5. Approve / reject ───────────────────────────────────────────────────

    @Test
    void approve_appliesDeactivationsAndCompletesImport() throws IOException {
        for (int i = 1; i <= 10; i++) {
            seedEmployee(T1, "E" + String.format("%03d", i), 1, BEFORE_SNAPSHOT);
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            csv.append(row("E" + String.format("%03d", i), 2, BEFORE_SNAPSHOT)).append("\n");
        }
        UUID id = doImport(T1, csv.toString().strip(), SNAPSHOT);
        assertThat(fetch(T1, id).status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);

        Optional<ImportRecord> approved = service.approve(T1, id, "operator-1");
        assertThat(approved).isPresent();
        assertThat(approved.get().status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(approved.get().approvedBy()).isEqualTo("operator-1");

        // Missing employees now deactivated.
        assertThat(statusOf(T1, "E008")).isEqualTo("INACTIVE");
        assertThat(statusOf(T1, "E009")).isEqualTo("INACTIVE");
        assertThat(statusOf(T1, "E010")).isEqualTo("INACTIVE");
    }

    @Test
    void reject_doesNotDeactivateAndSetsRejected() throws IOException {
        for (int i = 1; i <= 10; i++) {
            seedEmployee(T1, "E" + String.format("%03d", i), 1, BEFORE_SNAPSHOT);
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            csv.append(row("E" + String.format("%03d", i), 2, BEFORE_SNAPSHOT)).append("\n");
        }
        UUID id = doImport(T1, csv.toString().strip(), SNAPSHOT);

        Optional<ImportRecord> rejected = service.reject(T1, id, "operator-1");
        assertThat(rejected).isPresent();
        assertThat(rejected.get().status()).isEqualTo(ImportStatus.REJECTED);
        // No deactivations.
        assertThat(statusOf(T1, "E008")).isEqualTo("ACTIVE");
    }

    // ── 6. Duplicate employee ID ──────────────────────────────────────────────

    @Test
    void duplicateEmployeeId_needsReview_noDestructiveApply() throws IOException {
        seedEmployee(T1, "E001", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E002", 1, BEFORE_SNAPSHOT);

        // E001 appears twice.
        String csv = row("E001", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E001", 3, BEFORE_SNAPSHOT) + "\n"  // duplicate
                   + row("E002", 2, BEFORE_SNAPSHOT);
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.NEEDS_REVIEW);
        assertThat(r.holdReason()).isEqualTo(HoldReason.DUPLICATE_EMPLOYEE_ID.name());
        // No employees should be deactivated.
        assertThat(statusOf(T1, "E001")).isEqualTo("ACTIVE");
        assertThat(statusOf(T1, "E002")).isEqualTo("ACTIVE");
    }

    // ── 7. Missing employee ID in a row ──────────────────────────────────────

    @Test
    void rowWithNoEmployeeId_needsReview() throws IOException {
        // One valid row and one row with no employee ID.
        String csv = row("E001", 1, BEFORE_SNAPSHOT) + "\n"
                + ",1,2024-01-15T08:00:00Z,Bob,bob@example.com,+919000000002,,BLR,MORNING";
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.NEEDS_REVIEW);
        assertThat(r.holdReason()).isEqualTo(HoldReason.MISSING_EMPLOYEE_ID.name());
    }

    // ── 8. Malformed rows with valid rows mixed ───────────────────────────────

    @Test
    void malformedRows_validRowsStillStaged() throws IOException {
        // One invalid row (bad email), one valid row.
        String csv = "E001,1,2024-01-15T08:00:00Z,Alice,BADEMAIL,+919000000001,,BLR,MORNING\n"
                   + row("E002", 1, BEFORE_SNAPSHOT);
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        // E001 is invalid → rejected; E002 valid → staged and applied.
        assertThat(r.rejectedRows()).isEqualTo(1);
        assertThat(r.newCount()).isEqualTo(1);
        // E001 was seen (parseable ID), so NOT treated as missing.
        assertThat(r.missingCount()).isEqualTo(0);
        assertThat(statusOf(T1, "E002")).isEqualTo("ACTIVE");
    }

    // ── 9. Stale version: CSV row older than stored ───────────────────────────

    @Test
    void staleVersion_doesNotOverwriteNewerEmployee() throws IOException {
        // Seed at version 5.
        seedEmployee(T1, "E001", 5, BEFORE_SNAPSHOT);

        // CSV sends version 3 — older than stored.
        UUID id = doImport(T1, row("E001", 3, BEFORE_SNAPSHOT), SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        // Version must NOT have been downgraded.
        assertThat(versionOf(T1, "E001")).isEqualTo(5L);
    }

    // ── 10. Tenant isolation ──────────────────────────────────────────────────

    @Test
    void tenantIsolation_t2ImportDoesNotTouchT1Employees() throws IOException {
        seedEmployee(T1, "E001", 1, BEFORE_SNAPSHOT);

        // T2 imports a different CSV (no E001).
        UUID id = doImport(T2, row("E999", 1, BEFORE_SNAPSHOT), SNAPSHOT);
        ImportRecord r = fetch(T2, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        // E001 in T1 must be unaffected.
        assertThat(statusOf(T1, "E001")).isEqualTo("ACTIVE");
        // E999 created in T2.
        assertThat(statusOf(T2, "E999")).isEqualTo("ACTIVE");
    }

    // ── 11. Large-file simulation ─────────────────────────────────────────────

    @Test
    void largeFile_1000Rows_completesWithoutError() throws IOException {
        // 1000 new employees in a single import.
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            String id = "EMP" + String.format("%04d", i);
            csv.append(id).append(",1,2024-01-15T08:00:00Z,Employee ").append(id)
               .append(",emp").append(id.toLowerCase()).append("@example.com")
               .append(",+919000000001,,BLR,MORNING\n");
        }
        UUID importId = doImport(T1, csv.toString().strip(), SNAPSHOT);
        ImportRecord r = fetch(T1, importId);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        assertThat(r.newCount()).isEqualTo(1000);
        assertThat(r.rejectedRows()).isEqualTo(0);
        assertThat(r.totalRows()).isEqualTo(1000);
    }

    // ── 12. Import not found / wrong tenant ──────────────────────────────────

    @Test
    void findImport_wrongTenant_returnsEmpty() throws IOException {
        UUID id = doImport(T1, row("E001", 1, BEFORE_SNAPSHOT), SNAPSHOT);
        // T2 cannot see T1's import.
        assertThat(service.findImport(T2, id)).isEmpty();
    }

    @Test
    void approve_wrongTenant_returnsEmpty() throws IOException {
        // Seed enough missing to trigger threshold hold.
        for (int i = 1; i <= 10; i++) {
            seedEmployee(T1, "E" + i, 1, BEFORE_SNAPSHOT);
        }
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            csv.append(row("E" + i, 2, BEFORE_SNAPSHOT)).append("\n");
        }
        UUID id = doImport(T1, csv.toString().strip(), SNAPSHOT);
        assertThat(fetch(T1, id).status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);

        // T2 tries to approve T1's import.
        assertThat(service.approve(T2, id, "op")).isEmpty();
        // T1's import is unchanged.
        assertThat(fetch(T1, id).status()).isEqualTo(ImportStatus.AWAITING_APPROVAL);
    }

    // ── 13. Null source_updated_at — employee not deactivated (safety) ────────

    @Test
    void nullSourceUpdatedAt_employeeProtectedFromDeactivation() throws IOException {
        // Insert an employee without source_updated_at (simulates pre-V2 or incomplete HR data).
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, source_version,
                     source_updated_at, roster_hash, payload_hash, status)
                VALUES (?, ?, ?, ?, ?, NULL,
                        repeat('a', 64), repeat('b', 64), 'ACTIVE')
                """,
                T1, "E-NULL-TS", "No Timestamp", "nullts@example.com", 1L);

        // 4 normal employees so total is 5 active; importing 4 = 1 missing (20% < threshold).
        seedEmployee(T1, "E001", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E002", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E003", 1, BEFORE_SNAPSHOT);
        seedEmployee(T1, "E004", 1, BEFORE_SNAPSHOT);

        // Import only E001-E004; E-NULL-TS is "missing" but has no source_updated_at.
        String csv = row("E001", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E002", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E003", 2, BEFORE_SNAPSHOT) + "\n"
                   + row("E004", 2, BEFORE_SNAPSHOT);
        UUID id = doImport(T1, csv, SNAPSHOT);
        ImportRecord r = fetch(T1, id);

        assertThat(r.status()).isEqualTo(ImportStatus.COMPLETED);
        // E-NULL-TS is missing from the file but must NOT be deactivated — no source watermark.
        assertThat(statusOf(T1, "E-NULL-TS")).isEqualTo("ACTIVE");
        // missingCount still shows it as absent.
        assertThat(r.missingCount()).isEqualTo(1);
    }
}
