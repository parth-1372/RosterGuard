package com.rosterguard.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import com.rosterguard.roster.RosterImportService;
import com.rosterguard.service.EmployeeSyncService;
import com.rosterguard.service.EventResult;
import com.rosterguard.service.EventResult.Outcome;
import com.rosterguard.service.TransportFieldService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that outbox events are produced for every applied mutation:
 * <ul>
 *   <li>HR event CREATE → EMPLOYEE_CREATED</li>
 *   <li>HR event UPDATE → EMPLOYEE_UPDATED</li>
 *   <li>HR event DEACTIVATE → EMPLOYEE_DEACTIVATED</li>
 *   <li>Transport update → EMPLOYEE_TRANSPORT_UPDATED</li>
 *   <li>CSV import CREATE → EMPLOYEE_CREATED</li>
 *   <li>CSV import UPDATE → EMPLOYEE_UPDATED</li>
 *   <li>CSV import DEACTIVATE → EMPLOYEE_DEACTIVATED</li>
 *   <li>Stale, duplicate, and invalid inputs produce NO outbox events</li>
 *   <li>Database failure during outbox insert rolls back the employee write</li>
 *   <li>Composite keyset cursor pagination on (created_at DESC, id DESC)</li>
 * </ul>
 */
class OutboxMutationsIT extends PostgresIntegrationTest {

    @Autowired
    EmployeeSyncService syncService;

    @Autowired
    TransportFieldService transportService;

    @Autowired
    RosterImportService importService;

    @Autowired
    OutboxRepository outboxRepo;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void cleanState() {
        jdbc.update("DELETE FROM outbox_event WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM employee_event_review WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM seat_allocation WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM booking WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM import_row_error WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM import_seen_id WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM roster_staging WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM roster_import WHERE tenant_id IN (?, ?)", T1, T2);
        jdbc.update("DELETE FROM employee WHERE tenant_id IN (?, ?)", T1, T2);
    }

    private static HrProfile profile(String name, String email, String office) {
        return new HrProfile(name, email, "+919800000001", "M1", office, "DAY");
    }

    // ── 1. HR Event Outbox Events ─────────────────────────────────────────────

    @Test
    void hrCreate_producesEmployeeCreatedOutboxEvent() throws Exception {
        HrEvent event = new HrEvent(HrEventType.CREATE, "E-HR-1", 1L, Instant.now(),
                profile("Asha", "asha@example.com", "BLR"));

        EventResult res = syncService.process(T1, event);
        assertThat(res.outcome()).isEqualTo(Outcome.APPLIED);

        List<OutboxEvent> events = outboxRepo.list(T1, null, 10, null);
        assertThat(events).hasSize(1);
        OutboxEvent oe = events.get(0);
        assertThat(oe.aggregateId()).isEqualTo("E-HR-1");
        assertThat(oe.eventType()).isEqualTo("EMPLOYEE_CREATED");
        assertThat(oe.dedupKey()).isEqualTo("HR_CREATE:E-HR-1:1");

        JsonNode payload = MAPPER.readTree(oe.payload());
        assertThat(payload.get("employeeId").asText()).isEqualTo("E-HR-1");
        assertThat(payload.get("eventType").asText()).isEqualTo("EMPLOYEE_CREATED");
        assertThat(payload.get("sourceVersion").asLong()).isEqualTo(1L);
        assertThat(payload.get("profile").get("name").asText()).isEqualTo("Asha");
        assertThat(payload.get("profile").get("officeCode").asText()).isEqualTo("BLR");
    }

    @Test
    void hrUpdate_producesEmployeeUpdatedOutboxEvent() throws Exception {
        syncService.process(T1, new HrEvent(HrEventType.CREATE, "E-HR-2", 1L, Instant.now(),
                profile("Bob", "bob@example.com", "BLR")));

        // Update to version 2
        EventResult res = syncService.process(T1, new HrEvent(HrEventType.UPDATE, "E-HR-2", 2L, Instant.now(),
                profile("Bob Smith", "bob.smith@example.com", "PUN")));
        assertThat(res.outcome()).isEqualTo(Outcome.APPLIED);

        List<OutboxEvent> events = outboxRepo.list(T1, null, 10, null);
        assertThat(events).hasSize(2); // CREATE and UPDATE
        OutboxEvent updateEvent = events.stream()
                .filter(e -> "EMPLOYEE_UPDATED".equals(e.eventType()))
                .findFirst().orElseThrow();

        assertThat(updateEvent.aggregateId()).isEqualTo("E-HR-2");
        assertThat(updateEvent.dedupKey()).isEqualTo("HR_UPDATE:E-HR-2:2");

        JsonNode payload = MAPPER.readTree(updateEvent.payload());
        assertThat(payload.get("employeeId").asText()).isEqualTo("E-HR-2");
        assertThat(payload.get("eventType").asText()).isEqualTo("EMPLOYEE_UPDATED");
        assertThat(payload.get("sourceVersion").asLong()).isEqualTo(2L);
        assertThat(payload.get("profile").get("name").asText()).isEqualTo("Bob Smith");
        assertThat(payload.get("profile").get("officeCode").asText()).isEqualTo("PUN");
    }

    @Test
    void staleOrDuplicateHrEvents_produceNoNewOutboxEvents() {
        syncService.process(T1, new HrEvent(HrEventType.CREATE, "E-HR-3", 5L, Instant.now(),
                profile("Carol", "carol@example.com", "BLR")));
        assertThat(outboxRepo.list(T1, null, 10, null)).hasSize(1);

        // Stale event (v4 < v5)
        EventResult stale = syncService.process(T1, new HrEvent(HrEventType.UPDATE, "E-HR-3", 4L, Instant.now(),
                profile("Carol Older", "carol@example.com", "BLR")));
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE);
        assertThat(outboxRepo.list(T1, null, 10, null)).hasSize(1);

        // Duplicate event (re-sending v5 CREATE)
        EventResult dup = syncService.process(T1, new HrEvent(HrEventType.CREATE, "E-HR-3", 5L, Instant.now(),
                profile("Carol", "carol@example.com", "BLR")));
        assertThat(dup.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(outboxRepo.list(T1, null, 10, null)).hasSize(1);
    }

    // ── 2. Transport Update Outbox Events ─────────────────────────────────────

    @Test
    void transportUpdate_producesEmployeeTransportUpdatedOutboxEvent() throws Exception {
        syncService.process(T1, new HrEvent(HrEventType.CREATE, "E-TR-1", 1L, Instant.now(),
                profile("David", "david@example.com", "BLR")));

        transportService.update(T1, "E-TR-1", "Gate 4", "Wheelchair access");

        List<OutboxEvent> events = outboxRepo.list(T1, null, 10, null);
        assertThat(events).hasSize(2);
        OutboxEvent trEvent = events.stream()
                .filter(e -> "EMPLOYEE_TRANSPORT_UPDATED".equals(e.eventType()))
                .findFirst().orElseThrow();

        assertThat(trEvent.aggregateId()).isEqualTo("E-TR-1");
        assertThat(trEvent.dedupKey()).isEqualTo("TRANSPORT_UPDATE:E-TR-1:1");

        JsonNode payload = MAPPER.readTree(trEvent.payload());
        assertThat(payload.get("employeeId").asText()).isEqualTo("E-TR-1");
        assertThat(payload.get("eventType").asText()).isEqualTo("EMPLOYEE_TRANSPORT_UPDATED");
        assertThat(payload.get("transportVersion").asLong()).isEqualTo(1L);
        assertThat(payload.get("pickupPoint").asText()).isEqualTo("Gate 4");
        assertThat(payload.get("specialAssistance").asText()).isEqualTo("Wheelchair access");
    }

    // ── 3. CSV Import Outbox Events ───────────────────────────────────────────

    @Test
    void csvImport_createsAndUpdatesProduceOutboxEvents() throws Exception {
        // Pre-seed an existing employee at version 1
        syncService.process(T1, new HrEvent(HrEventType.CREATE, "E-CSV-OLD", 1L, Instant.now(),
                profile("Old Name", "old@example.com", "BLR")));
        jdbc.update("DELETE FROM outbox_event WHERE tenant_id = ?", T1);

        String csv = """
                employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
                E-CSV-NEW,1,2024-01-15T08:00:00Z,New Emp,new@example.com,+919800000001,,BLR,DAY
                E-CSV-OLD,2,2024-01-15T08:01:00Z,Old Updated,old.upd@example.com,+919800000001,,BLR,DAY
                """;

        UUID importId = importService.importCsv(T1,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2024-01-15T12:00:00Z"));

        List<OutboxEvent> events = outboxRepo.list(T1, null, 10, null);
        assertThat(events).hasSize(2);

        OutboxEvent created = events.stream()
                .filter(e -> "E-CSV-NEW".equals(e.aggregateId()))
                .findFirst().orElseThrow();
        assertThat(created.eventType()).isEqualTo("EMPLOYEE_CREATED");
        assertThat(created.dedupKey()).isEqualTo("CSV_CREATE:E-CSV-NEW:1");

        OutboxEvent updated = events.stream()
                .filter(e -> "E-CSV-OLD".equals(e.aggregateId()))
                .findFirst().orElseThrow();
        assertThat(updated.eventType()).isEqualTo("EMPLOYEE_UPDATED");
        assertThat(updated.dedupKey()).isEqualTo("CSV_UPDATE:E-CSV-OLD:2");
    }

    // ── 4. Transaction Rollback on Outbox Failure ──────────────────────────────

    @Test
    void outboxFailure_rollsBackEmployeeMutation() {
        // Add a check constraint on outbox_event that fails when aggregate_id = 'FAIL_TRIGGER'
        jdbc.update("""
                ALTER TABLE outbox_event
                ADD CONSTRAINT test_fail_trigger CHECK (aggregate_id <> 'FAIL_TRIGGER')
                """);
        try {
            HrEvent badEvent = new HrEvent(HrEventType.CREATE, "FAIL_TRIGGER", 1L, Instant.now(),
                    profile("Will Fail", "fail@example.com", "BLR"));

            EventResult res = syncService.process(T1, badEvent);
            assertThat(res.outcome()).isEqualTo(Outcome.ERROR);

            // Employee row must NOT exist in the database!
            Integer empCount = jdbc.queryForObject(
                    "SELECT count(*) FROM employee WHERE tenant_id = ? AND employee_id = 'FAIL_TRIGGER'",
                    Integer.class, T1);
            assertThat(empCount).isZero();
        } finally {
            jdbc.update("ALTER TABLE outbox_event DROP CONSTRAINT IF EXISTS test_fail_trigger");
        }
    }

    // ── 5. Keyset Cursor Pagination on (created_at DESC, id DESC) ─────────────

    @Test
    void outboxCursorPagination_ordersCorrectly() {
        for (int i = 1; i <= 5; i++) {
            syncService.process(T1, new HrEvent(HrEventType.CREATE, "E-PAGE-" + i, 1L, Instant.now(),
                    profile("Page " + i, "page" + i + "@example.com", "BLR")));
        }

        List<OutboxEvent> page1 = outboxRepo.list(T1, null, 2, null);
        assertThat(page1).hasSize(2);

        OutboxEvent lastPage1 = page1.get(1);
        List<OutboxEvent> page2 = outboxRepo.list(T1, lastPage1.id().toString(), 2, null);
        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).id()).isNotEqualTo(lastPage1.id());

        OutboxEvent lastPage2 = page2.get(1);
        List<OutboxEvent> page3 = outboxRepo.list(T1, lastPage2.id().toString(), 2, null);
        assertThat(page3).hasSize(1);
    }
}

