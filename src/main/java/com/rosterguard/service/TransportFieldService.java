package com.rosterguard.service;

import com.rosterguard.domain.FieldError;
import com.rosterguard.outbox.OutboxRepository;
import com.rosterguard.repository.EmployeeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transport-admin edits of transport-owned fields only. Cannot touch HR fields or the HR
 * version, because the underlying SQL does not name those columns.
 *
 * <p>Produces an EMPLOYEE_TRANSPORT_UPDATED outbox event in the same database transaction.
 */
@Service
public class TransportFieldService {

    static final int MAX_LENGTH = 500;

    private final EmployeeRepository employees;
    private final OutboxRepository outbox;

    public TransportFieldService(EmployeeRepository employees, OutboxRepository outbox) {
        this.employees = employees;
        this.outbox = outbox;
    }

    @Transactional
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
        String cleanPickup = blankToNull(pickupPoint);
        String cleanSpecial = blankToNull(specialAssistance);
        OptionalLong newVersion = employees.updateTransportWithVersion(tenantId, employeeId, cleanPickup, cleanSpecial);
        if (newVersion.isEmpty()) {
            throw new EmployeeNotFoundException(employeeId);
        }
        outbox.insertTransportUpdateEvent(tenantId, employeeId, newVersion.getAsLong(), cleanPickup, cleanSpecial);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
