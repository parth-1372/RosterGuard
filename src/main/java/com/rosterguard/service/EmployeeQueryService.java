package com.rosterguard.service;

import com.rosterguard.repository.EmployeeRecord;
import com.rosterguard.repository.EmployeeRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Tenant-scoped reads. Every call takes the tenant from the authenticated caller. */
@Service
public class EmployeeQueryService {

    public static final int MAX_PAGE_SIZE = 200;

    private final EmployeeRepository employees;

    public EmployeeQueryService(EmployeeRepository employees) {
        this.employees = employees;
    }

    public EmployeeRecord get(String tenantId, String employeeId) {
        return employees.find(tenantId, employeeId)
                .orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    public List<EmployeeRecord> list(String tenantId, String after, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return employees.list(tenantId, after == null ? "" : after, bounded);
    }
}

