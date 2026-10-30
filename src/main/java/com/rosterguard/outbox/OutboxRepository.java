package com.rosterguard.outbox;

import com.rosterguard.domain.HrProfile;
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
 * <p>Relay path uses {@code FOR UPDATE SKIP LOCKED} combined with an atomic lease update,
 * so that claimed rows are immediately leased (advancing next_attempt_at into the future)
 * before the claim transaction commits and releases row locks. This prevents concurrent workers
 * from double-claiming events even after the claim transaction commits.
 */
@Repository
public class OutboxRepository {

    static final int RELAY_BATCH = 10;
    static final int DEFAULT_LEASE_SECONDS = 60;

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

    // ── Writes (called inside mutation transactions) ─────────────────────────

    /** Insert an outbox event for an HR CREATE event. */
    public void insertCreateEvent(String tenantId, String employeeId, long version, HrProfile profile) {
        String dedupKey = "HR_CREATE:" + employeeId + ":" + version;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_CREATED","sourceVersion":%d,"profile":{"name":%s,"email":%s,"officeCode":%s,"shiftCode":%s}}"""
                .formatted(employeeId, version,
                        quote(profile == null ? null : profile.name()),
                        quote(profile == null ? null : profile.email()),
                        quote(profile == null ? null : profile.officeCode()),
                        quote(profile == null ? null : profile.shiftCode()));
        insertRaw(tenantId, employeeId, "EMPLOYEE_CREATED", dedupKey, payload);
    }

    /** Insert an outbox event for an HR UPDATE event. */
    public void insertUpdateEvent(String tenantId, String employeeId, long version, HrProfile profile) {
        String dedupKey = "HR_UPDATE:" + employeeId + ":" + version;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_UPDATED","sourceVersion":%d,"profile":{"name":%s,"email":%s,"officeCode":%s,"shiftCode":%s}}"""
                .formatted(employeeId, version,
                        quote(profile == null ? null : profile.name()),
                        quote(profile == null ? null : profile.email()),
                        quote(profile == null ? null : profile.officeCode()),
                        quote(profile == null ? null : profile.shiftCode()));
        insertRaw(tenantId, employeeId, "EMPLOYEE_UPDATED", dedupKey, payload);
    }

    /** Insert an outbox event for a Transport-owned field update. */
    public void insertTransportUpdateEvent(String tenantId, String employeeId, long transportVersion,
                                           String pickupPoint, String specialAssistance) {
        String dedupKey = "TRANSPORT_UPDATE:" + employeeId + ":" + transportVersion;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_TRANSPORT_UPDATED","transportVersion":%d,"pickupPoint":%s,"specialAssistance":%s}"""
                .formatted(employeeId, transportVersion, quote(pickupPoint), quote(specialAssistance));
        insertRaw(tenantId, employeeId, "EMPLOYEE_TRANSPORT_UPDATED", dedupKey, payload);
    }

    /**
     * Insert an outbox event in the same transaction as the employee deactivation.
     * The unique constraint on (tenant_id, dedup_key) is an ON CONFLICT DO NOTHING guard.
     */
    public void insertDeactivationEvent(String tenantId, String employeeId, long deactivationVersion) {
        String dedupKey = "DEACTIVATE:" + employeeId + ":" + deactivationVersion;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_DEACTIVATED","sourceVersion":%d}"""
                .formatted(employeeId, deactivationVersion);
        insertRaw(tenantId, employeeId, "EMPLOYEE_DEACTIVATED", dedupKey, payload);
    }

    /**
     * Insert a deactivation event for a CSV-import-triggered deactivation.
     */
    public void insertCsvDeactivationEvent(String tenantId, String employeeId, UUID importId) {
        String dedupKey = "CSV_DEACTIVATE:" + employeeId + ":" + importId;
        String payload = """
                {"employeeId":"%s","eventType":"EMPLOYEE_DEACTIVATED","importId":"%s"}"""
                .formatted(employeeId, importId);
        insertRaw(tenantId, employeeId, "EMPLOYEE_DEACTIVATED", dedupKey, payload);
    }

    private void insertRaw(String tenantId, String employeeId, String eventType, String dedupKey, String payload) {
        jdbc.update("""
                INSERT INTO outbox_event
                    (tenant_id, aggregate_id, event_type, dedup_key, payload)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (tenant_id, dedup_key) DO NOTHING
                """, tenantId, employeeId, eventType, dedupKey, payload);
    }

    private static String quote(String val) {
        return val == null ? "null" : "\"" + val.replace("\"", "\\\"") + "\"";
    }

    // ── Relay read path ───────────────────────────────────────────────────────

    /**
     * Atomically claim and lease up to {@link #RELAY_BATCH} PENDING events whose next_attempt_at is in the past.
     * {@code FOR UPDATE SKIP LOCKED} prevents concurrent workers from locking the same rows.
     * Within the same query, {@code next_attempt_at} is advanced by {@code leaseSeconds} into the future.
     */
    public List<OutboxEvent> claimPending(int leaseSeconds) {
        return jdbc.query("""
                WITH claimed AS (
                    SELECT tenant_id, id
                    FROM outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_event e
                SET next_attempt_at = now() + (CAST(? AS int) * interval '1 second')
                FROM claimed
                WHERE e.tenant_id = claimed.tenant_id AND e.id = claimed.id
                RETURNING e.tenant_id, e.id, e.aggregate_id, e.event_type, e.dedup_key, e.payload,
                          e.status, e.attempts, e.next_attempt_at, e.last_error, e.created_at, e.published_at
                """, MAPPER, RELAY_BATCH, leaseSeconds);
    }

    public List<OutboxEvent> claimPending() {
        return claimPending(DEFAULT_LEASE_SECONDS);
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
     */
    public void markForRetry(String tenantId, UUID id, int currentAttempts,
                             String errorClass, int maxAttempts) {
        int nextAttempts = currentAttempts + 1;
        if (nextAttempts >= maxAttempts) {
            jdbc.update("""
                    UPDATE outbox_event SET
                        status = 'FAILED', attempts = ?, last_error = ?, published_at = NULL
                    WHERE tenant_id = ? AND id = ?
                    """, nextAttempts, errorClass, tenantId, id);
        } else {
            long delaySec = Math.min((long) Math.pow(2, nextAttempts), 3600L);
            OffsetDateTime nextAt = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delaySec);
            jdbc.update("""
                    UPDATE outbox_event SET
                        attempts = ?, next_attempt_at = ?, last_error = ?
                    WHERE tenant_id = ? AND id = ?
                    """, nextAttempts, nextAt, errorClass, tenantId, id);
        }
    }

    // ── Monitoring read path with stable composite cursor pagination ─────────

    /**
     * Keyset-paginated outbox listing for OPERATOR monitoring.
     * Orders by (created_at DESC, id DESC) and uses matching composite cursor keys.
     */
    public List<OutboxEvent> list(String tenantId, String afterId, int limit,
                                  String statusFilter) {
        UUID afterUuid = null;
        OffsetDateTime afterCreatedAt = null;

        if (afterId != null && !afterId.isBlank()) {
            try {
                afterUuid = UUID.fromString(afterId.trim());
                List<OffsetDateTime> timestamps = jdbc.queryForList("""
                        SELECT created_at FROM outbox_event
                        WHERE tenant_id = ? AND id = ?
                        """, OffsetDateTime.class, tenantId, afterUuid);
                if (!timestamps.isEmpty()) {
                    afterCreatedAt = timestamps.get(0);
                } else {
                    // Cursor references nonexistent row for this tenant; return empty list
                    return List.of();
                }
            } catch (IllegalArgumentException e) {
                return List.of();
            }
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
        if (afterCreatedAt != null && afterUuid != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            params.add(afterCreatedAt);
            params.add(afterCreatedAt);
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
