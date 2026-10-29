package com.rosterguard.service;

import com.rosterguard.domain.FieldError;
import com.rosterguard.repository.EmployeeRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Transport-admin edits of transport-owned fields only. Cannot touch HR fields or the HR
 * version, because the underlying SQL does not name those columns.
 */
@Service
public class TransportFieldService {

    static final int MAX_LENGTH = 500;

    private final EmployeeRepository employees;

    public TransportFieldService(EmployeeRepository employees) {
        this.employees = employees;
    }

    public void update(String tenantId, String employeeId, String pickupPoint, String specialAssistance) {
        List<FieldError> errors = new ArrayList<>();
        if (pickupPoint != null && pickupPoint.length() > MAX_LENGTH) {
            errors.add(new FieldError("pickupPoint", "TOO_LONG"));
        }
        if (specialAssistance != null && specialAssistance.length() > MAX_LENGTH) {
            errors.add(new FieldError("specialAssistance", "TOO_LONG"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        if (!employees.updateTransport(tenantId, employeeId, blankToNull(pickupPoint),
                blankToNull(specialAssistance))) {
            throw new EmployeeNotFoundException(employeeId);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
