package com.rosterguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.FieldError;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import com.rosterguard.repository.EmployeeRecord;
import com.rosterguard.repository.EmployeeRepository;
import com.rosterguard.service.EventResult.Outcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EmployeeSyncServiceIT extends PostgresIntegrationTest {

    @Autowired
    EmployeeSyncService sync;
    @Autowired
    TransportFieldService transport;
    @Autowired
    EmployeeRepository employees;

    static HrEvent event(HrEventType type, String id, long version, String name, String office, String shift) {
        return new HrEvent(type, id, version, null,
                new HrProfile(name, id.toLowerCase() + "@example.test", "+919800000001", "M1", office, shift));
    }

    static HrEvent upd(String id, long version, String name, String office, String shift) {
        return event(HrEventType.UPDATE, id, version, name, office, shift);
    }

    EmployeeRecord get(String id) {
        return employees.find(T1, id).orElseThrow();
    }

    // ---- normal ----

    @Test
    void createThenUpdateApplies() {
        assertThat(sync.process(T1, event(HrEventType.CREATE, "E1", 1, "Asha", "BLR", "DAY")).outcome())
                .isEqualTo(Outcome.APPLIED);
        assertThat(sync.process(T1, upd("E1", 2, "Asha R", "PUN", "NIGHT")).outcome())
                .isEqualTo(Outcome.APPLIED);
        EmployeeRecord e = get("E1");
        assertThat(e.name()).isEqualTo("Asha R");
        assertThat(e.officeCode()).isEqualTo("PUN");
        assertThat(e.sourceVersion()).isEqualTo(2);
        assertThat(e.status()).isEqualTo("ACTIVE");
        assertThat(e.rosterHash()).hasSize(64);
    }

    // ---- stale: the PDF's example ----

    @Test
    void officeTransferV7BeforeShiftChangeV6_staleShiftChangeIsIgnored() {
        sync.process(T1, upd("E1", 5, "Asha", "BLR", "DAY"));
        // v7 (office transfer, new shift) arrives first
        assertThat(sync.process(T1, upd("E1", 7, "Asha", "PUN", "NIGHT")).outcome()).isEqualTo(Outcome.APPLIED);
        // v6 (older shift change, still in the old office) arrives late
        assertThat(sync.process(T1, upd("E1", 6, "Asha", "BLR", "EVENING")).outcome()).isEqualTo(Outcome.STALE);

        EmployeeRecord e = get("E1");
        assertThat(e.officeCode()).isEqualTo("PUN");
        assertThat(e.shiftCode()).isEqualTo("NIGHT");
        assertThat(e.sourceVersion()).isEqualTo(7);
    }

    // ---- duplicate / replay ----

    @Test
    void identicalDuplicateIsNoOpAndCreatesNoReview() {
        sync.process(T1, upd("E1", 3, "Asha", "BLR", "DAY"));
        EmployeeRecord before = get("E1");
        assertThat(sync.process(T1, upd("E1", 3, "Asha", "BLR", "DAY")).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(get("E1")).isEqualTo(before); // including applied_at: nothing was written
        assertThat(employees.countReviews(T1, "E1")).isZero();
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        List<HrEvent> feed = List.of(
                upd("E1", 1, "Asha", "BLR", "DAY"),
                upd("E2", 1, "Ravi", "PUN", "DAY"),
                upd("E1", 2, "Asha R", "PUN", "NIGHT"),
                new HrEvent(HrEventType.DEACTIVATE, "E2", 2L, null, null));
        sync.processBatch(T1, feed);
        EmployeeRecord e1 = get("E1");
        EmployeeRecord e2 = get("E2");

        List<EventResult> replay = sync.processBatch(T1, feed);

        assertThat(replay).extracting(EventResult::outcome)
                .containsExactly(Outcome.STALE, Outcome.STALE, Outcome.DUPLICATE, Outcome.DUPLICATE);
        assertThat(get("E1")).isEqualTo(e1);
        assertThat(get("E2")).isEqualTo(e2);
        assertThat(employees.countReviews(T1, "E1") + employees.countReviews(T1, "E2")).isZero();
    }

    // ---- same version, different payload ----

    @Test
    void conflictingSameVersionIsHeldForReviewAndDoesNotOverwrite() {
        sync.process(T1, upd("E1", 4, "Asha", "BLR", "DAY"));
        EmployeeRecord before = get("E1");

        EventResult result = sync.process(T1, upd("E1", 4, "Someone Else", "PUN", "NIGHT"));

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICT_HELD);
        assertThat(get("E1")).isEqualTo(before);
        assertThat(employees.countReviews(T1, "E1")).isEqualTo(1);
    }

    @Test
    void repeatingTheSameConflictDoesNotCreateAnotherReviewRow() {
        sync.process(T1, upd("E1", 4, "Asha", "BLR", "DAY"));
        sync.process(T1, upd("E1", 4, "Someone Else", "PUN", "NIGHT"));
        sync.process(T1, upd("E1", 4, "Someone Else", "PUN", "NIGHT"));
        assertThat(employees.countReviews(T1, "E1")).isEqualTo(1);
    }

    // ---- validation inside a batch ----

    @Test
    void invalidRecordIsRejectedWithoutBlockingValidOnesAndErrorsHoldNoPersonalData() {
        HrEvent bad = new HrEvent(HrEventType.UPDATE, "E2", 1L, null,
                new HrProfile("Ravi", "ravi@example.test", "12ab", "M1", "NOPE", "DAY"));
        List<EventResult> results = sync.processBatch(T1, List.of(
                upd("E1", 1, "Asha", "BLR", "DAY"), bad, upd("E3", 1, "Meera", "PUN", "DAY")));

        assertThat(results).extracting(EventResult::outcome)
                .containsExactly(Outcome.APPLIED, Outcome.REJECTED, Outcome.APPLIED);
        assertThat(results.get(1).errors()).containsExactlyInAnyOrder(
                new FieldError("phone", "INVALID_PHONE"), new FieldError("officeCode", "UNKNOWN_OFFICE"));
        assertThat(results.get(1).toString()).doesNotContain("12ab").doesNotContain("ravi@example.test");
        assertThat(employees.find(T1, "E2")).isEmpty();
        assertThat(employees.find(T1, "E3")).isPresent();
    }

    // ---- deactivation ----

    @Test
    void deactivateMarksInactive_staleDeactivateIsIgnored_higherUpdateReactivates() {
        sync.process(T1, upd("E1", 5, "Asha", "BLR", "DAY"));
        assertThat(sync.process(T1, new HrEvent(HrEventType.DEACTIVATE, "E1", 6L, null, null)).outcome())
                .isEqualTo(Outcome.APPLIED);
        assertThat(get("E1").status()).isEqualTo("INACTIVE");
        assertThat(get("E1").name()).isEqualTo("Asha"); // HR data retained

        // older updates arriving afterwards must not resurrect the employee
        assertThat(sync.process(T1, upd("E1", 5, "Asha", "BLR", "DAY")).outcome()).isEqualTo(Outcome.STALE);
        assertThat(sync.process(T1, upd("E1", 4, "Asha", "BLR", "DAY")).outcome()).isEqualTo(Outcome.STALE);
        // the same deactivate replayed is a no-op
        assertThat(sync.process(T1, new HrEvent(HrEventType.DEACTIVATE, "E1", 6L, null, null)).outcome())
                .isEqualTo(Outcome.DUPLICATE);
        assertThat(get("E1").status()).isEqualTo("INACTIVE");

        assertThat(sync.process(T1, upd("E1", 9, "Asha", "BLR", "DAY")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(get("E1").status()).isEqualTo("ACTIVE");
    }

    @Test
    void deactivateOfUnknownEmployeeIsRejected() {
        EventResult r = sync.process(T1, new HrEvent(HrEventType.DEACTIVATE, "GHOST", 1L, null, null));
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(r.errors()).containsExactly(new FieldError("employeeId", "UNKNOWN_EMPLOYEE"));
    }

    // ---- field ownership ----

    @Test
    void hrUpdateNeverOverwritesTransportFields() {
        sync.process(T1, upd("E1", 1, "Asha", "BLR", "DAY"));
        transport.update(T1, "E1", "Gate 4, Tech Park", "Wheelchair");

        sync.process(T1, upd("E1", 2, "Asha R", "PUN", "NIGHT"));

        EmployeeRecord e = get("E1");
        assertThat(e.name()).isEqualTo("Asha R");
        assertThat(e.pickupPoint()).isEqualTo("Gate 4, Tech Park");
        assertThat(e.specialAssistance()).isEqualTo("Wheelchair");
        assertThat(e.transportVersion()).isEqualTo(1);
    }

    @Test
    void hrDeactivateAndStaleEventsAlsoLeaveTransportFieldsAlone() {
        sync.process(T1, upd("E1", 3, "Asha", "BLR", "DAY"));
        transport.update(T1, "E1", "Gate 4", "None");
        sync.process(T1, upd("E1", 2, "Old", "PUN", "DAY"));
        sync.process(T1, new HrEvent(HrEventType.DEACTIVATE, "E1", 4L, null, null));
        EmployeeRecord e = get("E1");
        assertThat(e.pickupPoint()).isEqualTo("Gate 4");
        assertThat(e.specialAssistance()).isEqualTo("None");
    }

    @Test
    void transportUpdateNeverChangesHrFieldsOrHrVersion() {
        sync.process(T1, upd("E1", 7, "Asha", "BLR", "DAY"));
        EmployeeRecord before = get("E1");

        transport.update(T1, "E1", "Gate 9", "Ramp");

        EmployeeRecord after = get("E1");
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.email()).isEqualTo(before.email());
        assertThat(after.officeCode()).isEqualTo(before.officeCode());
        assertThat(after.shiftCode()).isEqualTo(before.shiftCode());
        assertThat(after.sourceVersion()).isEqualTo(7);
        assertThat(after.rosterHash()).isEqualTo(before.rosterHash());
        assertThat(after.payloadHash()).isEqualTo(before.payloadHash());
        assertThat(after.transportVersion()).isEqualTo(1);
    }

    @Test
    void transportUpdateValidatesLengthAndRequiresExistingEmployee() {
        sync.process(T1, upd("E1", 1, "Asha", "BLR", "DAY"));
        assertThatThrownBy(() -> transport.update(T1, "E1", "x".repeat(501), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transport.update(T1, "NOPE", "Gate", null))
                .isInstanceOf(EmployeeNotFoundException.class);
    }

    // ---- tenants ----

    @Test
    void sameEmployeeIdInTwoTenantsIsIndependent_andTransportCannotCrossTenants() {
        sync.process(T1, upd("E1", 5, "Asha", "BLR", "DAY"));
        sync.process(T2, upd("E1", 1, "Other Tenant", "PUN", "NIGHT"));
        assertThat(get("E1").sourceVersion()).isEqualTo(5);
        assertThat(employees.find(T2, "E1").orElseThrow().sourceVersion()).isEqualTo(1);

        sync.process(T1, upd("ONLY1", 1, "Asha", "BLR", "DAY"));
        assertThatThrownBy(() -> transport.update(T2, "ONLY1", "Gate", null))
                .isInstanceOf(EmployeeNotFoundException.class);
        assertThat(sync.process(T2, new HrEvent(HrEventType.DEACTIVATE, "ONLY1", 9L, null, null)).outcome())
                .isEqualTo(Outcome.REJECTED);
        assertThat(get("ONLY1").status()).isEqualTo("ACTIVE");
    }

    @Test
    void officeCodeFromAnotherTenantIsUnknown() {
        jdbc.update("INSERT INTO office (tenant_id, code, name) VALUES (?, 'HYD', 'Hyderabad')", T2);
        EventResult r = sync.process(T1, upd("E1", 1, "Asha", "HYD", "DAY"));
        assertThat(r.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(new ArrayList<>(r.errors())).containsExactly(new FieldError("officeCode", "UNKNOWN_OFFICE"));
    }
}
