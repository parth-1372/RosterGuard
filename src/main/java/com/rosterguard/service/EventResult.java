package com.rosterguard.service;

import com.rosterguard.domain.FieldError;
import java.util.List;

/** Per-event result. Contains the employee ID and codes only, never personal data. */
public record EventResult(String employeeId, Outcome outcome, List<FieldError> errors) {

    public enum Outcome {
        APPLIED,
        DUPLICATE,
        STALE,
        /** Same version, different payload: stored for review, employee state unchanged. */
        CONFLICT_HELD,
        REJECTED,
        /** Unexpected failure for this event only; other events in the batch are unaffected. */
        ERROR
    }

    public static EventResult of(String employeeId, Outcome outcome) {
        return new EventResult(employeeId, outcome, List.of());
    }

    public static EventResult rejected(String employeeId, List<FieldError> errors) {
        return new EventResult(employeeId, Outcome.REJECTED, List.copyOf(errors));
    }
}
