package com.rosterguard.api;

import com.rosterguard.domain.FieldError;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * One event as sent by the HR system. There are deliberately no transport-owned fields here:
 * anything like "pickupPoint" in an HR payload is ignored, so HR cannot write those columns.
 * type and sourceUpdatedAt are strings so that one malformed value rejects only this record,
 * not the whole batch.
 */
public record EventDto(String type, String employeeId, Long sourceVersion, String sourceUpdatedAt,
                       String name, String email, String phone, String managerId,
                       String officeCode, String shiftCode) {

    /** The domain event, or the format errors that stop it being built. */
    public record Parsed(HrEvent event, List<FieldError> errors) {
    }

    public Parsed toDomain() {
        HrEventType parsedType = null;
        if (type != null) {
            try {
                parsedType = HrEventType.valueOf(type.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // left null: the validator reports INVALID_TYPE
            }
        }
        Instant parsedUpdatedAt = null;
        if (sourceUpdatedAt != null && !sourceUpdatedAt.isBlank()) {
            try {
                parsedUpdatedAt = Instant.parse(sourceUpdatedAt.trim());
            } catch (DateTimeParseException e) {
                return new Parsed(null, List.of(new FieldError("sourceUpdatedAt", "INVALID_FORMAT")));
            }
        }
        HrEvent event = new HrEvent(parsedType, employeeId, sourceVersion, parsedUpdatedAt,
                new HrProfile(name, email, phone, managerId, officeCode, shiftCode));
        return new Parsed(event, List.of());
    }
}

