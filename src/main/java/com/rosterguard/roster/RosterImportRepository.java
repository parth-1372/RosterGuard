package com.rosterguard.roster;

import com.rosterguard.outbox.DeactivationRepository;
import com.rosterguard.outbox.OutboxRepository;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * All SQL for roster import lifecycle. Every statement leads with tenant_id.
 *
 * <p>Staging uses JDBC batch updates so that 200k rows do not issue 200k individual network
 * round-trips. The batch size is configurable via {@link #BATCH_SIZE}.
 */
@Repository
public class RosterImportRepository {

    static final int BATCH_SIZE = 1000;

    private static final RowMapper<ImportRecord> IMPORT_MAPPER = (rs, i) -> new ImportRecord(
            rs.getString("tenant_id"),
            (UUID) rs.getObject("id"),
            ImportStatus.valueOf(rs.getString("status")),
            fromDb(rs.getObject("snapshot_generated_at", OffsetDateTime.class)),
            rs.getObject("active_count_at_start") == null ? null
                    : rs.getInt("active_count_at_start"),
            rs.getInt("total_rows"),
            rs.getInt("rejected_rows"),
            rs.getInt("new_count"),
            rs.getInt("changed_count"),
            rs.getInt("missing_count"),
            rs.getString("hold_reason"),
            rs.getString("approved_by"),
            fromDb(rs.getObject("created_at", OffsetDateTime.class)),
            fromDb(rs.getObject("completed_at", OffsetDateTime.class))
    );

    private final JdbcTemplate jdbc;
    private final DeactivationRepository deactivations;
    private final OutboxRepository outbox;

    public RosterImportRepository(JdbcTemplate jdbc, DeactivationRepository deactivations,
                                  OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.deactivations = deactivations;
        this.outbox = outbox;
    }

    // ── Import lifecycle ──────────────────────────────────────────────────────

    /** Create a new import record in STAGING state. Returns the generated UUID. */
    public UUID create(String tenantId, Instant snapshotGeneratedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO roster_import (tenant_id, id, status, snapshot_generated_at)
                VALUES (?, ?, 'STAGING', ?)
                """,
                tenantId, id, toDb(snapshotGeneratedAt));
        return id;
    }

    public Optional<ImportRecord> find(String tenantId, UUID importId) {
        return jdbc.query("""
                SELECT tenant_id, id, status, snapshot_generated_at, active_count_at_start,
                       total_rows, rejected_rows, new_count, changed_count, missing_count,
                       hold_reason, approved_by, created_at, completed_at
                FROM roster_import WHERE tenant_id = ? AND id = ?
                """, IMPORT_MAPPER, tenantId, importId).stream().findFirst();
    }

    /** Persist staging statistics and set final state after processing is complete. */
    public void finalise(String tenantId, UUID importId, ImportStatus status,
                         int activeCountAtStart, int totalRows, int rejectedRows,
                         int newCount, int changedCount, int missingCount,
                         HoldReason holdReason) {
        jdbc.update("""
                UPDATE roster_import SET
                    status = ?, active_count_at_start = ?, total_rows = ?,
                    rejected_rows = ?, new_count = ?, changed_count = ?, missing_count = ?,
                    hold_reason = ?, completed_at = now()
                WHERE tenant_id = ? AND id = ?
                """,
                status.name(), activeCountAtStart, totalRows, rejectedRows,
                newCount, changedCount, missingCount,
                holdReason == null ? null : holdReason.name(),
                tenantId, importId);
    }

    /** Mark AWAITING_APPROVAL import as COMPLETED or REJECTED by an operator. */
    public boolean approve(String tenantId, UUID importId, String operatorId) {
        return jdbc.update("""
                UPDATE roster_import SET status = 'COMPLETED', approved_by = ?, completed_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'AWAITING_APPROVAL'
                """, operatorId, tenantId, importId) > 0;
    }

    public boolean reject(String tenantId, UUID importId, String operatorId) {
        return jdbc.update("""
                UPDATE roster_import SET status = 'REJECTED', approved_by = ?, completed_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'AWAITING_APPROVAL'
                """, operatorId, tenantId, importId) > 0;
    }

    /** Mark as FAILED (unexpected error during processing). */
    public void markFailed(String tenantId, UUID importId) {
        jdbc.update("""
                UPDATE roster_import SET status = 'FAILED', completed_at = now()
                WHERE tenant_id = ? AND id = ?
                """, tenantId, importId);
    }

    // ── Staging inserts ───────────────────────────────────────────────────────

    /**
     * Batch-insert valid rows into roster_staging.
     * Each batch of {@link #BATCH_SIZE} rows is sent as one JDBC batch statement.
     */
    public void stageBatch(String tenantId, UUID importId, List<CsvRow> rows) {
        if (rows.isEmpty()) return;
        java.util.Map<String, CsvRow> uniqueRows = new java.util.LinkedHashMap<>();
        for (CsvRow r : rows) {
            uniqueRows.putIfAbsent(r.employeeId(), r);
        }
        List<CsvRow> batch = new ArrayList<>(uniqueRows.values());
        jdbc.batchUpdate("""
                INSERT INTO roster_staging
                    (tenant_id, import_id, employee_id, name, email, phone,
                     manager_id, office_code, shift_code,
                     source_version, source_updated_at, roster_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, import_id, employee_id) DO NOTHING
                """,
                batch,
                BATCH_SIZE,
                (PreparedStatement ps, CsvRow r) -> {
                    ps.setString(1, tenantId);
                    ps.setObject(2, importId);
                    ps.setString(3, r.employeeId());
                    ps.setString(4, r.profile().name());
                    ps.setString(5, r.profile().email());
                    ps.setString(6, r.profile().phone());
                    ps.setString(7, r.profile().managerId());
                    ps.setString(8, r.profile().officeCode());
                    ps.setString(9, r.profile().shiftCode());
                    ps.setLong(10, r.sourceVersion());
                    ps.setObject(11, toDb(r.sourceUpdatedAt()));
                    ps.setString(12, r.rosterHash());
                });
    }

    /**
     * Record every usable employee ID, incrementing occurrences for duplicates.
     * Call this even for invalid rows — any row with a parseable ID must not be classified
     * as missing.
     */
    public void recordSeenIds(String tenantId, UUID importId, List<String> employeeIds) {
        if (employeeIds.isEmpty()) return;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String id : employeeIds) {
            counts.merge(id, 1, Integer::sum);
        }
        List<java.util.Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());

        jdbc.batchUpdate("""
                INSERT INTO import_seen_id (tenant_id, import_id, employee_id, occurrences)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, import_id, employee_id)
                DO UPDATE SET occurrences = import_seen_id.occurrences + EXCLUDED.occurrences
                """,
                entries,
                BATCH_SIZE,
                (PreparedStatement ps, java.util.Map.Entry<String, Integer> entry) -> {
                    ps.setString(1, tenantId);
                    ps.setObject(2, importId);
                    ps.setString(3, entry.getKey());
                    ps.setInt(4, entry.getValue());
                });
    }

    /** Insert row-level error records (field + code only, never the raw value). */
    public void recordErrors(String tenantId, UUID importId, long rowNumber,
                             String employeeId, List<com.rosterguard.domain.FieldError> errors) {
        for (com.rosterguard.domain.FieldError e : errors) {
            jdbc.update("""
                    INSERT INTO import_row_error
                        (tenant_id, import_id, row_number, employee_id, field, code)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    tenantId, importId, rowNumber, employeeId, e.field(), e.code());
        }
    }

    // ── Diff queries (set-based) ──────────────────────────────────────────────

    /** Count active employees in this tenant (the denominator for the safety threshold). */
    public int countActive(String tenantId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    /**
     * True if any employee ID appears more than once across all rows for this import
     * (valid or invalid). This is the signal to put the import into NEEDS_REVIEW.
     */
    public boolean hasDuplicateSeenIds(String tenantId, UUID importId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM import_seen_id
                WHERE tenant_id = ? AND import_id = ? AND occurrences > 1
                """, Integer.class, tenantId, importId);
        return n != null && n > 0;
    }

    /**
     * Count how many active employees are absent from the staged snapshot.
     * An employee is "missing" if they are ACTIVE in employee table and their ID is not in
     * import_seen_id for this import (not just roster_staging — seen_id covers even invalid rows).
     */
    public int countMissing(String tenantId, UUID importId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM employee e
                WHERE e.tenant_id = ?
                  AND e.status = 'ACTIVE'
                  AND e.employee_id NOT IN (
                      SELECT s.employee_id FROM import_seen_id s
                      WHERE s.tenant_id = ?
                        AND s.import_id = ?
                  )
                """, Integer.class, tenantId, tenantId, importId);
        return n == null ? 0 : n;
    }

    /**
     * Count staged rows whose roster_hash differs from the current employee row — these are CHANGED.
     */
    public int countChanged(String tenantId, UUID importId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM roster_staging rs
                JOIN employee e ON e.tenant_id = rs.tenant_id AND e.employee_id = rs.employee_id
                WHERE rs.tenant_id = ? AND rs.import_id = ?
                  AND rs.roster_hash <> e.roster_hash
                """, Integer.class, tenantId, importId);
        return n == null ? 0 : n;
    }

    /**
     * Count staged rows for employees that do not exist in the employee table — these are NEW.
     */
    public int countNew(String tenantId, UUID importId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM roster_staging rs
                WHERE rs.tenant_id = ? AND rs.import_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM employee e
                      WHERE e.tenant_id = rs.tenant_id AND e.employee_id = rs.employee_id
                  )
                """, Integer.class, tenantId, importId);
        return n == null ? 0 : n;
    }

    /**
     * Apply the staged snapshot: upsert CHANGED and NEW employees in a single set-based
     * INSERT ... ON CONFLICT, guarded by the source-version condition.
     *
     * <p>Only rows whose staged sourceVersion is strictly greater than the stored sourceVersion
     * are written. This mirrors the API-event path's atomicity guarantee.
     */
    public int applyUpserts(String tenantId, UUID importId) {
        return jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, phone, manager_id,
                     office_code, shift_code, status, source_version, source_updated_at,
                     roster_hash, payload_hash)
                SELECT rs.tenant_id, rs.employee_id, rs.name, rs.email, rs.phone, rs.manager_id,
                       rs.office_code, rs.shift_code, 'ACTIVE',
                       rs.source_version, rs.source_updated_at, rs.roster_hash,
                       -- payload_hash for a CSV row: same algorithm, type = CREATE
                       rs.roster_hash  -- reuse roster_hash as payload_hash for CSV-originated rows
                FROM roster_staging rs
                WHERE rs.tenant_id = ? AND rs.import_id = ?
                ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
                    name = EXCLUDED.name, email = EXCLUDED.email, phone = EXCLUDED.phone,
                    manager_id = EXCLUDED.manager_id, office_code = EXCLUDED.office_code,
                    shift_code = EXCLUDED.shift_code, status = 'ACTIVE',
                    source_version = EXCLUDED.source_version,
                    source_updated_at = EXCLUDED.source_updated_at,
                    roster_hash = EXCLUDED.roster_hash,
                    payload_hash = EXCLUDED.payload_hash,
                    applied_at = now()
                WHERE employee.source_version < EXCLUDED.source_version
                """, tenantId, importId);
    }

    /**
     * Deactivate employees who are ACTIVE and absent from the snapshot, but only when their
     * {@code source_updated_at} is known and earlier than or equal to {@code snapshotGeneratedAt}
     * (old-file protection: D1b).
     *
     * <p>Employees whose {@code source_updated_at} is NULL are intentionally skipped: without a
     * source watermark we cannot determine whether the snapshot is authoritative for that employee,
     * so deactivation is unsafe.
     *
     * <p>An employee whose HR record is newer than the snapshot is also NOT deactivated — we
     * cannot trust that the snapshot is authoritative for them.
     *
     * <p>For each deactivated employee, future bookings are cancelled, seats released, and an
     * outbox event is inserted in the caller's transaction.
     *
     * @return number of employees deactivated
     */
    public int applyDeactivations(String tenantId, UUID importId, Instant snapshotGeneratedAt) {
        List<String> deactivatedIds = jdbc.queryForList("""
                UPDATE employee e SET
                    status = 'INACTIVE',
                    applied_at = now()
                WHERE e.tenant_id = ?
                  AND e.status = 'ACTIVE'
                  AND e.employee_id NOT IN (
                      SELECT s.employee_id FROM import_seen_id s
                      WHERE s.tenant_id = ?
                        AND s.import_id = ?
                  )
                  AND e.source_updated_at IS NOT NULL
                  AND e.source_updated_at <= ?
                RETURNING e.employee_id
                """, String.class, tenantId, tenantId, importId, toDb(snapshotGeneratedAt));

        for (String empId : deactivatedIds) {
            List<UUID> cancelledBookings = deactivations.cancelFutureBookings(tenantId, empId);
            deactivations.releaseSeats(tenantId, cancelledBookings);
            outbox.insertCsvDeactivationEvent(tenantId, empId, importId);
        }
        return deactivatedIds.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
