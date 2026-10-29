package com.rosterguard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HrEventValidatorTest {

    private static final Set<String> OFFICES = Set.of("BLR", "PUN");

    private static HrEvent update(String id, Long version, String email, String phone, String office) {
        return new HrEvent(HrEventType.UPDATE, id, version, null,
                new HrProfile("Asha", email, phone, "M1", office, "DAY"));
    }

    @Test
    void validEventHasNoErrors() {
        assertThat(HrEventValidator.validate(
                update("E1", 1L, "a@x.test", "+919800000001", "BLR"), OFFICES)).isEmpty();
    }

    @Test
    void malformedPhoneIsReportedByFieldAndCodeOnly() {
        List<FieldError> errors = HrEventValidator.validate(
                update("E1", 1L, "a@x.test", "12ab", "BLR"), OFFICES);
        assertThat(errors).containsExactly(new FieldError("phone", "INVALID_PHONE"));
        assertThat(errors.toString()).doesNotContain("12ab");
    }

    @Test
    void unknownOfficeIsReported() {
        assertThat(HrEventValidator.validate(
                update("E1", 1L, "a@x.test", "+919800000001", "NOPE"), OFFICES))
                .containsExactly(new FieldError("officeCode", "UNKNOWN_OFFICE"));
    }

    @Test
    void multipleErrorsAreAllReported() {
        assertThat(HrEventValidator.validate(update("E1", 1L, "bad", "x", "NOPE"), OFFICES))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("email", "phone", "officeCode");
    }

    @Test
    void missingVersionAndIdAreRejected() {
        assertThat(HrEventValidator.validate(
                update(null, null, "a@x.test", "+919800000001", "BLR"), OFFICES))
                .extracting(FieldError::field)
                .containsExactlyInAnyOrder("employeeId", "sourceVersion");
    }

    @Test
    void negativeVersionIsRejected() {
        assertThat(HrEventValidator.validate(
                update("E1", -1L, "a@x.test", "+919800000001", "BLR"), OFFICES))
                .containsExactly(new FieldError("sourceVersion", "INVALID_FORMAT"));
    }

    @Test
    void deactivateNeedsOnlyIdAndVersion() {
        HrEvent deactivate = new HrEvent(HrEventType.DEACTIVATE, "E1", 5L, null, null);
        assertThat(HrEventValidator.validate(deactivate, OFFICES)).isEmpty();
    }

    @Test
    void invalidEmployeeIdFormatIsRejected() {
        assertThat(HrEventValidator.validate(
                update("E 1;DROP", 1L, "a@x.test", "+919800000001", "BLR"), OFFICES))
                .containsExactly(new FieldError("employeeId", "INVALID_FORMAT"));
    }
}
