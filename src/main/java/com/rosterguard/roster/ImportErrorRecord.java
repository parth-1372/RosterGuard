package com.rosterguard.roster;

/**
 * PII-safe record representing a row validation error during roster CSV import.
 * Contains only structural fields and error codes, never raw input values.
 */
public record ImportErrorRecord(
        long id,
        long rowNumber,
        String employeeId,
        String field,
        String code
) {}

