package com.rosterguard.repository;

import java.time.Instant;

/** A full read of one employee row. Used by services and tests; carries personal data, never log it. */
public record EmployeeRecord(
        String employeeId, String name, String email, String phone, String managerId,
        String officeCode, String shiftCode, String status, long sourceVersion,
        Instant sourceUpdatedAt, String rosterHash, String payloadHash, Instant appliedAt,
        String pickupPoint, String specialAssistance, long transportVersion) {
}
