package com.rosterguard.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.roster.RosterImportService;
import com.rosterguard.service.EmployeeSyncService;
import com.rosterguard.service.EventResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rigorous transaction boundary verification.
 * Forces database failures at:
 * <ul>
 *   <li>1. Booking cancellation</li>
 *   <li>2. Seat allocation release</li>
 *   <li>3. Outbox event insertion</li>
 * </ul>
 * For both:
 * <ul>
 *   <li>HR event deactivation stream ({@link EmployeeSyncService})</li>
 *   <li>CSV nightly import deactivation ({@link RosterImportService})</li>
 * </ul>
 * Verifies that in every case, all partial changes are atomically rolled back:
 * employee remains ACTIVE, booking remains CONFIRMED, seat remains allocated,
 * and no outbox events exist.
 */
class DeactivationTransactionRollbackIT extends PostgresIntegrationTest {

    @Autowired
    EmployeeSyncService syncService;

    @Autowired
    RosterImportService importService;

    private static final Instant T_BEFORE = Instant.parse("2024-01-15T10:00:00Z");
    private static final Instant T_SNAPSHOT = Instant.parse("2024-01-15T12:00:00Z");

    @AfterEach
    void cleanTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_booking ON booking");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_seat ON seat_allocation");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_outbox ON outbox_event");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_operation()");
    }

    private void createFailFunction() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_operation() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'FORCED_FAILURE_FOR_TEST';
                END;
                $$ LANGUAGE plpgsql;
                """);
    }

    private void seedEmployee(String tenantId, String empId) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, phone, office_code, status,
                     source_version, source_updated_at, roster_hash, payload_hash)
                VALUES (?, ?, 'Emp ' || ?, 'emp@example.com', '+919000000001', 'BLR', 'ACTIVE',
                        1, ?, repeat('a', 64), repeat('b', 64))
                ON CONFLICT (tenant_id, employee_id) DO NOTHING
                """, tenantId, empId, empId, java.sql.Timestamp.from(T_BEFORE));
    }

    private UUID seedBookingAndSeat(String tenantId, String empId) {
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO booking (tenant_id, id, employee_id, start_time, status)
                VALUES (?, ?, ?, now() + interval '1 day', 'CONFIRMED')
                """, tenantId, bookingId, empId);
        jdbc.update("""
                INSERT INTO seat_allocation (tenant_id, booking_id, seat_id)
                VALUES (?, ?, 'SEAT-TEST')
                """, tenantId, bookingId);
        return bookingId;
    }

    private String buildCsvWithOneMissing(String presentId, String missingId) {
        return "employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode\n"
                + presentId + ",2,2024-01-15T10:00:00Z,Present,pres@example.com,+919000000001,,BLR,MORNING\n";
    }

    // ── 1. Force failure at BOOKING CANCELLATION ───────────────────────────────

    @Test
    void hrEvent_failureAtBookingCancellation_rollsBackEntireTransaction() {
        seedEmployee(T1, "FAIL-B1");
        UUID bookingId = seedBookingAndSeat(T1, "FAIL-B1");

        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_fail_booking BEFORE UPDATE ON booking FOR EACH ROW EXECUTE FUNCTION fail_operation()");

        // HR deactivation event should catch the driver exception and return Outcome.ERROR
        EventResult result = syncService.process(T1,
                new HrEvent(HrEventType.DEACTIVATE, "FAIL-B1", 2L, T_BEFORE.plus(1, ChronoUnit.HOURS), null));
        assertThat(result.outcome()).isEqualTo(EventResult.Outcome.ERROR);

        // Entire transaction rolled back:
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?", String.class, T1, "FAIL-B1"))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT source_version FROM employee WHERE tenant_id = ? AND employee_id = ?", Long.class, T1, "FAIL-B1"))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE tenant_id = ? AND id = ?", String.class, T1, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NULL FROM seat_allocation WHERE tenant_id = ? AND booking_id = ?", Boolean.class, T1, bookingId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, T1, "FAIL-B1"))
                .isEqualTo(0);
    }

    @Test
    void csvImport_failureAtBookingCancellation_rollsBackEntireTransaction() {
        // Seed 4 present and 1 missing (1/5 = 20% < 30% threshold so deactivation attempts immediately)
        for (int i = 1; i <= 5; i++) seedEmployee(T1, "CSV-B" + i);
        UUID bookingId = seedBookingAndSeat(T1, "CSV-B5"); // CSV-B5 will be missing from CSV

        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_fail_booking BEFORE UPDATE ON booking FOR EACH ROW EXECUTE FUNCTION fail_operation()");

        StringBuilder csv = new StringBuilder("employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode\n");
        for (int i = 1; i <= 4; i++) {
            csv.append("CSV-B").append(i).append(",2,2024-01-15T10:00:00Z,Name,em@example.com,+919000000001,,BLR,MORNING\n");
        }

        try {
            importService.importCsv(T1, new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), T_SNAPSHOT);
        } catch (Exception expected) {
            // Expected failure
        }

        // CSV-B5 deactivation was rolled back:
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?", String.class, T1, "CSV-B5"))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE tenant_id = ? AND id = ?", String.class, T1, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NULL FROM seat_allocation WHERE tenant_id = ? AND booking_id = ?", Boolean.class, T1, bookingId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, T1, "CSV-B5"))
                .isEqualTo(0);
    }

    // ── 2. Force failure at SEAT RELEASE ───────────────────────────────────────

    @Test
    void hrEvent_failureAtSeatRelease_rollsBackEntireTransaction() {
        seedEmployee(T1, "FAIL-S1");
        UUID bookingId = seedBookingAndSeat(T1, "FAIL-S1");

        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_fail_seat BEFORE UPDATE ON seat_allocation FOR EACH ROW EXECUTE FUNCTION fail_operation()");

        EventResult result = syncService.process(T1,
                new HrEvent(HrEventType.DEACTIVATE, "FAIL-S1", 2L, T_BEFORE.plus(1, ChronoUnit.HOURS), null));
        assertThat(result.outcome()).isEqualTo(EventResult.Outcome.ERROR);

        // Rolled back
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?", String.class, T1, "FAIL-S1"))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE tenant_id = ? AND id = ?", String.class, T1, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NULL FROM seat_allocation WHERE tenant_id = ? AND booking_id = ?", Boolean.class, T1, bookingId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, T1, "FAIL-S1"))
                .isEqualTo(0);
    }

    @Test
    void csvImport_failureAtSeatRelease_rollsBackEntireTransaction() {
        for (int i = 1; i <= 5; i++) seedEmployee(T1, "CSV-S" + i);
        UUID bookingId = seedBookingAndSeat(T1, "CSV-S5");

        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_fail_seat BEFORE UPDATE ON seat_allocation FOR EACH ROW EXECUTE FUNCTION fail_operation()");

        StringBuilder csv = new StringBuilder("employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode\n");
        for (int i = 1; i <= 4; i++) {
            csv.append("CSV-S").append(i).append(",2,2024-01-15T10:00:00Z,Name,em@example.com,+919000000001,,BLR,MORNING\n");
        }

        try {
            importService.importCsv(T1, new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), T_SNAPSHOT);
        } catch (Exception expected) {
        }

        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?", String.class, T1, "CSV-S5"))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE tenant_id = ? AND id = ?", String.class, T1, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NULL FROM seat_allocation WHERE tenant_id = ? AND booking_id = ?", Boolean.class, T1, bookingId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, T1, "CSV-S5"))
                .isEqualTo(0);
    }

    // ── 3. Force failure at OUTBOX INSERTION ───────────────────────────────────

    @Test
    void hrEvent_failureAtOutboxInsertion_rollsBackEntireTransaction() {
        seedEmployee(T1, "FAIL-O1");
        UUID bookingId = seedBookingAndSeat(T1, "FAIL-O1");

        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_fail_outbox BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE FUNCTION fail_operation()");

        EventResult result = syncService.process(T1,
                new HrEvent(HrEventType.DEACTIVATE, "FAIL-O1", 2L, T_BEFORE.plus(1, ChronoUnit.HOURS), null));
        assertThat(result.outcome()).isEqualTo(EventResult.Outcome.ERROR);

        // Rolled back
        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?", String.class, T1, "FAIL-O1"))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE tenant_id = ? AND id = ?", String.class, T1, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NULL FROM seat_allocation WHERE tenant_id = ? AND booking_id = ?", Boolean.class, T1, bookingId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, T1, "FAIL-O1"))
                .isEqualTo(0);
    }

    @Test
    void csvImport_failureAtOutboxInsertion_rollsBackEntireTransaction() {
        for (int i = 1; i <= 5; i++) seedEmployee(T1, "CSV-O" + i);
        UUID bookingId = seedBookingAndSeat(T1, "CSV-O5");

        createFailFunction();
        jdbc.execute("CREATE TRIGGER trg_fail_outbox BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE FUNCTION fail_operation()");

        StringBuilder csv = new StringBuilder("employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode\n");
        for (int i = 1; i <= 4; i++) {
            csv.append("CSV-O").append(i).append(",2,2024-01-15T10:00:00Z,Name,em@example.com,+919000000001,,BLR,MORNING\n");
        }

        try {
            importService.importCsv(T1, new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)), T_SNAPSHOT);
        } catch (Exception expected) {
        }

        assertThat(jdbc.queryForObject("SELECT status FROM employee WHERE tenant_id = ? AND employee_id = ?", String.class, T1, "CSV-O5"))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE tenant_id = ? AND id = ?", String.class, T1, bookingId))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT released_at IS NULL FROM seat_allocation WHERE tenant_id = ? AND booking_id = ?", Boolean.class, T1, bookingId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND aggregate_id = ?", Integer.class, T1, "CSV-O5"))
                .isEqualTo(0);
    }
}

