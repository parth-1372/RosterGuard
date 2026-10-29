package com.rosterguard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Evidence for docs/DESIGN_DECISIONS.md D10: the database itself enforces tenant scoping. */
class TenantScopingSchemaIT extends PostgresIntegrationTest {

    private void seedEmployeeInTenant1() {
        jdbc.update("INSERT INTO employee (tenant_id, employee_id, source_version, roster_hash, payload_hash) "
                + "VALUES (?, 'E1', 1, repeat('a', 64), repeat('b', 64))", T1);
    }

    @Test
    void everyTableExceptTenantLeadsItsPrimaryKeyWithTenantId() {
        List<String> offenders = jdbc.queryForList("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind = 'r'
                  AND c.relname NOT IN ('tenant', 'flyway_schema_history')
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                    WHERE i.indrelid = c.oid AND i.indisprimary AND a.attname = 'tenant_id')
                """, String.class);
        assertThat(offenders).isEmpty();
    }

    @Test
    void bookingCannotPointAtAnotherTenantsEmployee() {
        seedEmployeeInTenant1();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO booking (tenant_id, employee_id, start_time) VALUES (?, 'E1', now())", T2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reviewRecordCannotPointAtAnotherTenantsEmployee() {
        seedEmployeeInTenant1();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO employee_event_review (tenant_id, employee_id, event_type, source_version,
                    stored_payload_hash, incoming_payload_hash, payload)
                VALUES (?, 'E1', 'UPDATE', 1, repeat('a', 64), repeat('c', 64), '{}'::jsonb)
                """, T2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxEventCannotPointAtAnotherTenantsEmployee() {
        seedEmployeeInTenant1();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO outbox_event (tenant_id, aggregate_id, event_type, dedup_key, payload)
                VALUES (?, 'E1', 'EMPLOYEE_CHANGED', 'k', '{}'::jsonb)
                """, T2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stagingSeenIdAndErrorRowsCannotPointAtAnotherTenantsImport() {
        UUID importId = jdbc.queryForObject(
                "INSERT INTO roster_import (tenant_id) VALUES (?) RETURNING id", UUID.class, T1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO roster_staging (tenant_id, import_id, employee_id, source_version, roster_hash)
                VALUES (?, ?, 'E1', 1, repeat('a', 64))
                """, T2, importId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO import_seen_id (tenant_id, import_id, employee_id) VALUES (?, ?, 'E1')",
                T2, importId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO import_row_error (tenant_id, import_id, row_number, code) VALUES (?, ?, 1, 'X')",
                T2, importId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatAllocationCannotPointAtAnotherTenantsBooking() {
        seedEmployeeInTenant1();
        UUID bookingId = jdbc.queryForObject(
                "INSERT INTO booking (tenant_id, employee_id, start_time) VALUES (?, 'E1', now()) RETURNING id",
                UUID.class, T1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO seat_allocation (tenant_id, booking_id, seat_id) VALUES (?, ?, 'S1')",
                T2, bookingId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void employeeCannotReferenceAnotherTenantsOffice() {
        jdbc.update("INSERT INTO office (tenant_id, code, name) VALUES (?, 'HYD', 'Hyderabad')", T2);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO employee (tenant_id, employee_id, office_code, source_version, roster_hash, payload_hash)
                VALUES (?, 'E1', 'HYD', 1, repeat('a', 64), repeat('b', 64))
                """, T1)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
