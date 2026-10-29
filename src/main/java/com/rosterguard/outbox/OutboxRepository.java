package com.rosterguard.outbox;

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
 * All SQL for the outbox_event table.
 *
 * <p>Relay path uses {@code FOR UPDATE SKIP LOCKED} so that concurrent relay threads claim
 * different rows and never double-deliver. Batch size is kept small (10) so lock contention
 * windows are short and the relay stays responsive under backpressure.
 */
@Repository
public class OutboxRepository {

    static final int RELAY_BATCH = 10;

    private static final RowMapper<OutboxEvent> MAPPER = (rs, i) -> new OutboxEvent(
            rs.getString("tenant_id"),
            rs.getObject("id", UUID.class),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("dedup_key"),
            rs.getString("payload"),
            rs.getString("status"),
            rs.getInt("attempts"),
            fromDb(rs.getObject("next_attempt_at", OffsetDateTime.class)),
            rs.getString("last_error"),
            fromDb(rs.getObject("created_at", OffsetDateTime.class)),
            fromDb(rs.getObject("published_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Write (called inside the deactivation transaction) ────────────────────

    /**
     * Insert an outbox event in the same transaction as the employee deactivation.
     * The unique constraint on (tenant_id, dedup_key) is an ON CONFLICT DO NOTHING guard:
     * a duplicate deactivation signal does not create a duplicate event row.
     *
     * @param tenantId            tenant scope
     * @param employeeId          aggregate root
     * @param deactivationVersion source version that triggered this deactivation (part of dedup key)
     */
    public void insertDeactivationEvent(String tenantId, String employeeId,
                                        long deactivationVersion) {
        String dedupKey = "DEACTIVATE:" + employeeId + ":" + deactivationVersion;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_DEACTIVATED","sourceVersion":%d}"""
                .formatted(employeeId, deactivationVersion);
        jdbc.update("""
                INSERT INTO outbox_event
                    (tenant_id, aggregate_id, event_type, dedup_key, payload)
                VALUES (?, ?, 'EMPLOYEE_DEACTIVATED', ?, CAST(? AS jsonb))
                ON CONFLICT (tenant_id, dedup_key) DO NOTHING
                """, tenantId, employeeId, dedupKey, payload);
    }

    /**
     * Insert a deactivation event for a CSV-import-triggered deactivation. The dedup key
     * includes the import ID so it is distinct from HR-event deactivations of the same employee.
     */
    public void insertCsvDeactivationEvent(String tenantId, String employeeId,
                                           UUID importId) {
        String dedupKey = "CSV_DEACTIVATE:" + employeeId + ":" + importId;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_DEACTIVATED","importId":"%s"}"""
                .formatted(employeeId, importId);
        jdbc.update("""
                INSERT INTO outbox_event
                    (tenant_id, aggregate_id, event_type, dedup_key, payload)
                VALUES (?, ?, 'EMPLOYEE_DEACTIVATED', ?, CAST(? AS jsonb))
                ON CONFLICT (tenant_id, dedup_key) DO NOTHING
                """, tenantId, employeeId, dedupKey, payload);
    }

    // ── Relay read path ───────────────────────────────────────────────────────

    /**
     * Claim up to {@link #RELAY_BATCH} PENDING events whose next_attempt_at is in the past.
     * {@code FOR UPDATE SKIP LOCKED} prevents two concurrent relay workers from claiming the
     * same row: the second worker skips locked rows and picks the next available one.
     *
     * <p>Must be called inside a transaction that is held open while the publish call runs,
     * so the row remains locked until the status is updated.
     */
    public List<OutboxEvent> claimPending() {
        return jdbc.query("""
                SELECT tenant_id, id, aggregate_id, event_type, dedup_key, payload,
                       status, attempts, next_attempt_at, last_error, created_at, published_at
                FROM outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, MAPPER, RELAY_BATCH);
    }

    /** Mark an event as successfully published. */
    public void markPublished(String tenantId, UUID id) {
        jdbc.update("""
                UPDATE outbox_event SET
                    status = 'PUBLISHED', published_at = now(), last_error = NULL
                WHERE tenant_id = ? AND id = ?
                """, tenantId, id);
    }

    /**
     * Schedule a retry with exponential backoff: delay = 2^attempts seconds, capped at 1 hour.
     * Increments the attempt counter and records the error reason (class name only — no message
     * that might contain PII or driver internals).
     */
    public void markForRetry(String tenantId, UUID id, int currentAttempts,
                             String errorClass, int maxAttempts) {
        int nextAttempts = currentAttempts + 1;
        if (nextAttempts >= maxAttempts) {
            // Dead-letter: stop retrying.
            jdbc.update("""
                    UPDATE outbox_event SET
                        status = 'FAILED', attempts = ?, last_error = ?, published_at = NULL
                    WHERE tenant_id = ? AND id = ?
                    """, nextAttempts, errorClass, tenantId, id);
        } else {
            // Exponential backoff: 2^attempt seconds, max 3600.
            long delaySec = Math.min((long) Math.pow(2, nextAttempts), 3600L);
            OffsetDateTime nextAt = OffsetDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(delaySec);
            jdbc.update("""
                    UPDATE outbox_event SET
                        attempts = ?, next_attempt_at = ?, last_error = ?
                    WHERE tenant_id = ? AND id = ?
                    """, nextAttempts, nextAt, errorClass, tenantId, id);
        }
    }

    // ── Monitoring read path ──────────────────────────────────────────────────

    /**
     * Keyset-paginated outbox listing for OPERATOR monitoring.
     * Returns events for the given tenant, ordered by created_at DESC (newest first).
     * An optional status filter narrows to PENDING / PUBLISHED / FAILED.
     */
    public List<OutboxEvent> list(String tenantId, String afterId, int limit,
                                  String statusFilter) {
        UUID afterUuid = null;
        if (afterId != null && !afterId.isBlank()) {
            afterUuid = UUID.fromString(afterId);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT tenant_id, id, aggregate_id, event_type, dedup_key, payload,
                       status, attempts, next_attempt_at, last_error, created_at, published_at
                FROM outbox_event
                WHERE tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" AND status = ?");
            params.add(statusFilter);
        }
        if (afterUuid != null) {
            sql.append(" AND id < ?");
            params.add(afterUuid);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);

        return jdbc.query(sql.toString(), MAPPER, params.toArray());
    }

    public Optional<OutboxEvent> find(String tenantId, UUID id) {
        return jdbc.query("""
                SELECT tenant_id, id, aggregate_id, event_type, dedup_key, payload,
                       status, attempts, next_attempt_at, last_error, created_at, published_at
                FROM outbox_event WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Instant fromDb(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
