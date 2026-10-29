package com.rosterguard.service;

public class EmployeeNotFoundException extends RuntimeException {

    /** The message never includes personal data; the ID is not personal data. */
    public EmployeeNotFoundException(String employeeId) {
        super("Employee not found: " + employeeId);
    }
}
