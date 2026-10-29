package com.rosterguard.api;

import com.rosterguard.repository.EmployeeRecord;

/** What the API returns for an employee. Internal hashes are not exposed. */
public record EmployeeView(String employeeId, String name, String email, String phone,
                           String managerId, String officeCode, String shiftCode, String status,
                           long sourceVersion, String pickupPoint, String specialAssistance) {

    public static EmployeeView from(EmployeeRecord e) {
        return new EmployeeView(e.employeeId(), e.name(), e.email(), e.phone(), e.managerId(),
                e.officeCode(), e.shiftCode(), e.status(), e.sourceVersion(),
                e.pickupPoint(), e.specialAssistance());
    }
}

