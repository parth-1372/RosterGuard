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
                     source_version, source_updated_at, roster_hash, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    ps.setString(13, r.payloadHash());
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

    /** Keyset-paginated retrieval of row errors for operator error reports. */
    public List<ImportErrorRecord> listErrors(String tenantId, UUID importId, Long after, int limit) {
        if (after == null) {
            return jdbc.query("""
                    SELECT id, row_number, employee_id, field, code
                    FROM import_row_error
                    WHERE tenant_id = ? AND import_id = ?
                    ORDER BY id ASC
                    LIMIT ?
                    """, (rs, i) -> new ImportErrorRecord(
                            rs.getLong("id"),
                            rs.getLong("row_number"),
                            rs.getString("employee_id"),
                            rs.getString("field"),
                            rs.getString("code")
                    ), tenantId, importId, limit);
        } else {
            return jdbc.query("""
                    SELECT id, row_number, employee_id, field, code
                    FROM import_row_error
                    WHERE tenant_id = ? AND import_id = ? AND id > ?
                    ORDER BY id ASC
                    LIMIT ?
                    """, (rs, i) -> new ImportErrorRecord(
                            rs.getLong("id"),
                            rs.getLong("row_number"),
                            rs.getString("employee_id"),
                            rs.getString("field"),
                            rs.getString("code")
                    ), tenantId, importId, after, limit);
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
     * Uses employee_id NOT IN (...) which PostgreSQL optimizes with a fast Hash Anti-Join.
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
     * Count staged rows whose roster_hash differs from the current employee row AND whose source_version
     * is strictly newer than stored — these are CHANGED.
     */
    public int countChanged(String tenantId, UUID importId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM roster_staging rs
                JOIN employee e ON e.tenant_id = rs.tenant_id AND e.employee_id = rs.employee_id
                WHERE rs.tenant_id = ? AND rs.import_id = ?
                  AND rs.roster_hash <> e.roster_hash
                  AND rs.source_version > e.source_version
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
     * <p>Simultaneously inserts EMPLOYEE_CREATED and EMPLOYEE_UPDATED outbox events for all applied
     * rows in the same transaction using a writable Common Table Expression (CTE).
     *
     * @return number of employee rows inserted or updated
     */
    public int applyUpserts(String tenantId, UUID importId) {
        Integer count = jdbc.queryForObject("""
                WITH upserted AS (
                    INSERT INTO employee
                        (tenant_id, employee_id, name, email, phone, manager_id,
                         office_code, shift_code, status, source_version, source_updated_at,
                         roster_hash, payload_hash)
                    SELECT rs.tenant_id, rs.employee_id, rs.name, rs.email, rs.phone, rs.manager_id,
                           rs.office_code, rs.shift_code, 'ACTIVE',
                           rs.source_version, rs.source_updated_at, rs.roster_hash,
                           rs.payload_hash
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
                    RETURNING employee.tenant_id, employee.employee_id, employee.source_version,
                              employee.name, employee.email, employee.phone, employee.manager_id,
                              employee.office_code, employee.shift_code,
                              (xmax = 0) AS is_new
                ),
                outbox_ins AS (
                    INSERT INTO outbox_event (tenant_id, aggregate_id, event_type, dedup_key, payload)
                    SELECT u.tenant_id, u.employee_id,
                           CASE WHEN u.is_new THEN 'EMPLOYEE_CREATED' ELSE 'EMPLOYEE_UPDATED' END,
                           CASE WHEN u.is_new THEN 'CSV_CREATE:' || u.employee_id || ':' || u.source_version
                                ELSE 'CSV_UPDATE:' || u.employee_id || ':' || u.source_version END,
                           json_build_object(
                               'employeeId', u.employee_id,
                               'eventType', CASE WHEN u.is_new THEN 'EMPLOYEE_CREATED' ELSE 'EMPLOYEE_UPDATED' END,
                               'sourceVersion', u.source_version,
                               'profile', json_build_object(
                                   'name', u.name,
                                   'email', u.email,
                                   'phone', u.phone,
                                   'managerId', u.manager_id,
                                   'officeCode', u.office_code,
                                   'shiftCode', u.shift_code
                               )
                           )::jsonb
                    FROM upserted u
                    ON CONFLICT (tenant_id, dedup_key) DO NOTHING
                )
                SELECT count(*) FROM upserted
                """, Integer.class, tenantId, importId);
        return count == null ? 0 : count;
    }

    /**
     * Deactivate employees who are ACTIVE and absent from the snapshot, but only when their
     * {@code source_updated_at} is known and earlier than or equal to {@code snapshotGeneratedAt}
     * (old-file protection: D1b).
     *
     * <p>Executes deactivation, future booking cancellation, seat release, and outbox event insertion
     * atomically in a single set-based SQL query via writable CTEs.
     *
     * @return number of employees deactivated
     */
    public int applyDeactivations(String tenantId, UUID importId, Instant snapshotGeneratedAt) {
        Integer count = jdbc.queryForObject("""
                WITH deactivated AS (
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
                    RETURNING e.tenant_id, e.employee_id
                ),
                cancelled_bookings AS (
                    UPDATE booking b SET
                        status = 'CANCELLED',
                        cancelled_at = now()
                    FROM deactivated d
                    WHERE b.tenant_id = d.tenant_id
                      AND b.employee_id = d.employee_id
                      AND b.status = 'CONFIRMED'
                      AND b.start_time > now()
                    RETURNING b.tenant_id, b.id AS booking_id
                ),
                released_seats AS (
                    UPDATE seat_allocation sa SET
                        released_at = now()
                    FROM cancelled_bookings cb
                    WHERE sa.tenant_id = cb.tenant_id
                      AND sa.booking_id = cb.booking_id
                      AND sa.released_at IS NULL
                    RETURNING sa.tenant_id, sa.booking_id
                ),
                outbox_ins AS (
                    INSERT INTO outbox_event (tenant_id, aggregate_id, event_type, dedup_key, payload)
                    SELECT d.tenant_id, d.employee_id, 'EMPLOYEE_DEACTIVATED',
                           'CSV_DEACTIVATE:' || d.employee_id || ':' || CAST(? AS text),
                           json_build_object(
                               'employeeId', d.employee_id,
                               'eventType', 'EMPLOYEE_DEACTIVATED',
                               'importId', CAST(? AS text)
                           )::jsonb
                    FROM deactivated d
                    ON CONFLICT (tenant_id, dedup_key) DO NOTHING
                )
                SELECT count(*) FROM deactivated
                """, Integer.class, tenantId, tenantId, importId, toDb(snapshotGeneratedAt),
                importId.toString(), importId.toString());
        return count == null ? 0 : count;
    }

    // ── Tenant Snapshot Watermark ─────────────────────────────────────────────

    public Optional<Instant> getLatestSnapshotWatermark(String tenantId) {
        List<OffsetDateTime> list = jdbc.query(
                "SELECT latest_snapshot_watermark FROM tenant WHERE id = ?",
                (rs, i) -> rs.getObject(1, OffsetDateTime.class),
                tenantId);
        return list.isEmpty() || list.get(0) == null ? Optional.empty() : Optional.of(list.get(0).toInstant());
    }

    public void updateLatestSnapshotWatermark(String tenantId, Instant watermark) {
        if (watermark == null) return;
        jdbc.update("""
                UPDATE tenant
                SET latest_snapshot_watermark = GREATEST(COALESCE(latest_snapshot_watermark, ?), ?)
                WHERE id = ?
                """, toDb(watermark), toDb(watermark), tenantId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
