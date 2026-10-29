package com.rosterguard.domain;

import java.time.Instant;

/**
 * One change pushed by the HR system. {@code sourceVersion} is the HR-supplied per-employee
 * monotonic version (never synthesised by us). {@code sourceUpdatedAt} is the HR-side change
 * time, used only as a watermark for old-file protection, never for ordering events.
 * For DEACTIVATE the profile is ignored.
 */
public record HrEvent(HrEventType type, String employeeId, Long sourceVersion,
                      Instant sourceUpdatedAt, HrProfile profile) {

    public HrEvent {
        employeeId = employeeId == null ? null : employeeId.trim();
        profile = profile == null ? HrProfile.EMPTY : profile;
    }

    /** The profile that actually counts for this event type. */
    public HrProfile effectiveProfile() {
        return type == HrEventType.DEACTIVATE ? HrProfile.EMPTY : profile;
    }
}
