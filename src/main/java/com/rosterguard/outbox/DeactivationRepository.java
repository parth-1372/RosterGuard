package com.rosterguard.outbox;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Side-effect SQL for employee deactivation: booking cancellation and seat release.
 *
 * <p>Every method in this class must be called inside the same database transaction as the
 * {@code employee} status update, so a partial failure rolls back all changes atomically.
 * The caller (EmployeeSyncService or RosterImportRepository) is responsible for the transaction.
 *
 * <p>Only future bookings (start_time > now()) are cancelled. Past or in-progress bookings
 * are left intact for audit purposes.
 */
@Repository
public class DeactivationRepository {

    private static final Logger log = LoggerFactory.getLogger(DeactivationRepository.class);

    private final JdbcTemplate jdbc;

    public DeactivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cancel all CONFIRMED future bookings for an employee.
     *
     * @return list of cancelled booking UUIDs (used to release their seats)
     */
    public List<UUID> cancelFutureBookings(String tenantId, String employeeId) {
        List<UUID> ids = jdbc.queryForList("""
                UPDATE booking SET status = 'CANCELLED', cancelled_at = now()
                WHERE tenant_id = ? AND employee_id = ?
                  AND status = 'CONFIRMED' AND start_time > now()
                RETURNING id
                """, UUID.class, tenantId, employeeId);
        if (!ids.isEmpty()) {
            log.info("bookings cancelled tenant={} employee={} count={}",
                    tenantId, employeeId, ids.size());
        }
        return ids;
    }

    /**
     * Release the seat allocations for the given booking IDs.
     * Only unreleased seats (released_at IS NULL) are touched.
     *
     * @return number of seats released
     */
    public int releaseSeats(String tenantId, List<UUID> bookingIds) {
        if (bookingIds.isEmpty()) return 0;
        int released = 0;
        for (UUID bookingId : bookingIds) {
            released += jdbc.update("""
                    UPDATE seat_allocation SET released_at = now()
                    WHERE tenant_id = ? AND booking_id = ? AND released_at IS NULL
                    """, tenantId, bookingId);
        }
        if (released > 0) {
            log.info("seats released tenant={} count={}", tenantId, released);
        }
        return released;
    }
}
