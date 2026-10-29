package com.rosterguard.repository;

import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * All SQL for employees. Every statement filters on tenant_id.
 *
 * <p>HR statements name only HR columns and transport statements name only transport columns,
 * so field ownership is enforced structurally: an HR write cannot touch transport data.
 */
@Repository
public class EmployeeRepository {

    /** Stored version info needed to classify an event that did not apply. */
    public record VersionInfo(long sourceVersion, String payloadHash) {
    }

    private static final RowMapper<EmployeeRecord> MAPPER = EmployeeRepository::map;

    private final JdbcTemplate jdbc;

    public EmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or update the HR fields, only if the incoming version is strictly newer.
     * The version check and the write are one atomic statement: a concurrent older update
     * cannot overwrite a newer one. Transport columns are not mentioned, so they are untouched.
     *
     * @return true if the row was inserted or updated; false if stored version is &gt;= incoming
     */
    public boolean upsertHr(String tenantId, String employeeId, long version, Instant sourceUpdatedAt,
                            HrProfile p, String rosterHash, String payloadHash) {
        return jdbc.update("""
                INSERT INTO employee (tenant_id, employee_id, name, email, phone, manager_id,
                    office_code, shift_code, status, source_version, source_updated_at,
                    roster_hash, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
                    name = EXCLUDED.name, email = EXCLUDED.email, phone = EXCLUDED.phone,
                    manager_id = EXCLUDED.manager_id, office_code = EXCLUDED.office_code,
                    shift_code = EXCLUDED.shift_code, status = 'ACTIVE',
                    source_version = EXCLUDED.source_version,
                    source_updated_at = EXCLUDED.source_updated_at,
                    roster_hash = EXCLUDED.roster_hash, payload_hash = EXCLUDED.payload_hash,
                    applied_at = now()
                WHERE employee.source_version < EXCLUDED.source_version
                """,
                tenantId, employeeId, p.name(), p.email(), p.phone(), p.managerId(),
                p.officeCode(), p.shiftCode(), version, toDb(sourceUpdatedAt),
                rosterHash, payloadHash) > 0;
    }

    /** Mark inactive only if the incoming version is strictly newer. HR profile fields are kept. */
    public boolean deactivate(String tenantId, String employeeId, long version,
                              Instant sourceUpdatedAt, String payloadHash) {
        return jdbc.update("""
                UPDATE employee SET status = 'INACTIVE', source_version = ?,
                    source_updated_at = ?, payload_hash = ?, applied_at = now()
                WHERE tenant_id = ? AND employee_id = ? AND source_version < ?
                """,
                version, toDb(sourceUpdatedAt), payloadHash, tenantId, employeeId, version) > 0;
    }

    public Optional<VersionInfo> findVersionInfo(String tenantId, String employeeId) {
        return jdbc.query("""
                SELECT source_version, payload_hash FROM employee
                WHERE tenant_id = ? AND employee_id = ?
                """,
                (rs, i) -> new VersionInfo(rs.getLong(1), rs.getString(2)),
                tenantId, employeeId).stream().findFirst();
    }

    /** Keep a same-version, different-payload event for human review. Idempotent per payload. */
    public void saveConflictForReview(String tenantId, String employeeId, HrEventType type,
                                      long version, String storedPayloadHash,
                                      String incomingPayloadHash, String payloadJson) {
        jdbc.update("""
                INSERT INTO employee_event_review (tenant_id, employee_id, event_type, source_version,
                    stored_payload_hash, incoming_payload_hash, payload)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (tenant_id, employee_id, source_version, incoming_payload_hash) DO NOTHING
                """,
                tenantId, employeeId, type.name(), version, storedPayloadHash,
                incomingPayloadHash, payloadJson);
    }

    /** Transport-owned fields only. transport_version is incremented atomically by the server. */
    public boolean updateTransport(String tenantId, String employeeId,
                                   String pickupPoint, String specialAssistance) {
        return jdbc.update("""
                UPDATE employee SET pickup_point = ?, special_assistance = ?,
                    transport_version = transport_version + 1
                WHERE tenant_id = ? AND employee_id = ?
                """,
                pickupPoint, specialAssistance, tenantId, employeeId) > 0;
    }

    public Optional<EmployeeRecord> find(String tenantId, String employeeId) {
        return jdbc.query("""
                SELECT employee_id, name, email, phone, manager_id, office_code, shift_code, status,
                       source_version, source_updated_at, roster_hash, payload_hash, applied_at,
                       pickup_point, special_assistance, transport_version
                FROM employee WHERE tenant_id = ? AND employee_id = ?
                """, MAPPER, tenantId, employeeId).stream().findFirst();
    }

    public long countReviews(String tenantId, String employeeId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM employee_event_review
                WHERE tenant_id = ? AND employee_id = ?
                """, Long.class, tenantId, employeeId);
        return count == null ? 0 : count;
    }

    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static EmployeeRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new EmployeeRecord(
                rs.getString("employee_id"), rs.getString("name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("manager_id"), rs.getString("office_code"),
                rs.getString("shift_code"), rs.getString("status"), rs.getLong("source_version"),
                fromDb(rs, "source_updated_at"), rs.getString("roster_hash"),
                rs.getString("payload_hash"), fromDb(rs, "applied_at"),
                rs.getString("pickup_point"), rs.getString("special_assistance"),
                rs.getLong("transport_version"));
    }
}
