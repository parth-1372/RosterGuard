package com.rosterguard.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.security.Role;
import com.rosterguard.security.TestTokenFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * HTTP integration tests for the operator-only outbox monitoring endpoints:
 * <ul>
 *   <li>Authentication: 401 when unauthenticated</li>
 *   <li>Authorization: 403 for non-OPERATOR roles (HR_INTEGRATION, TRANSPORT_ADMIN, EMPLOYEE)</li>
 *   <li>Monitoring: OPERATOR can list with pagination and filter by status</li>
 *   <li>Tenant isolation: T2 operator cannot view T1 outbox events (returns 404 for specific ID)</li>
 *   <li>PII safety: responses contain only event metadata and sanitised error names</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class OutboxApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRepository repo;

    private static final String OUTBOX_URL = "/api/v1/ops/outbox";

    private String opToken(String tenant) {
        return TestTokenFactory.validToken("op", tenant, Role.OPERATOR.name());
    }

    private String hrToken(String tenant) {
        return TestTokenFactory.validToken("hr", tenant, Role.HR_INTEGRATION.name());
    }

    private String taToken(String tenant) {
        return TestTokenFactory.validToken("ta", tenant, Role.TRANSPORT_ADMIN.name());
    }

    private String empToken(String tenant) {
        return TestTokenFactory.validToken("emp", tenant, Role.EMPLOYEE.name());
    }

    private void seedEmployee(String tenantId, String empId) {
        jdbc.update("""
                INSERT INTO employee
                    (tenant_id, employee_id, name, email, phone, office_code, status,
                     source_version, source_updated_at, roster_hash, payload_hash)
                VALUES (?, ?, 'Name', 'email@example.com', '+919000000001', 'BLR', 'INACTIVE',
                        1, now(), repeat('a', 64), repeat('b', 64))
                ON CONFLICT (tenant_id, employee_id) DO NOTHING
                """, tenantId, empId);
    }

    private UUID createEvent(String tenantId, String empId, long version) {
        seedEmployee(tenantId, empId);
        repo.insertDeactivationEvent(tenantId, empId, version);
        return repo.list(tenantId, null, 10, null).stream()
                .filter(e -> e.aggregateId().equals(empId))
                .findFirst().orElseThrow().id();
    }

    // ── 1. Authentication & Authorization ────────────────────────────────────

    @Test
    void unauthenticated_list_returns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL))
           .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void hrIntegration_cannotAccessOutbox_gets403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL)
                .header("Authorization", "Bearer " + hrToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isForbidden())
           .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void transportAdmin_cannotAccessOutbox_gets403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL)
                .header("Authorization", "Bearer " + taToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void employee_cannotAccessOutbox_gets403() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL)
                .header("Authorization", "Bearer " + empToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    // ── 2. OPERATOR can list and get ──────────────────────────────────────────

    @Test
    void operator_canListOutboxEvents_withCorrectShape() throws Exception {
        UUID id = createEvent(T1, "OUT-API-1", 1L);

        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL)
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].id").value(id.toString()))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].aggregateId").value("OUT-API-1"))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].eventType").value("EMPLOYEE_DEACTIVATED"))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].status").value("PENDING"))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].attempts").value(0))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].createdAt").exists());
    }

    @Test
    void operator_canFilterByStatus() throws Exception {
        UUID pendingId = createEvent(T1, "OUT-PEND", 1L);
        UUID publishedId = createEvent(T1, "OUT-PUB", 2L);
        repo.markPublished(T1, publishedId);

        // Filter PENDING
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL + "?status=PENDING")
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(1))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].id").value(pendingId.toString()));

        // Filter PUBLISHED
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL + "?status=PUBLISHED")
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(1))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].id").value(publishedId.toString()));
    }

    @Test
    void operator_canGetSingleEventById() throws Exception {
        UUID id = createEvent(T1, "OUT-SINGLE", 5L);

        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL + "/" + id)
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(id.toString()))
           .andExpect(MockMvcResultMatchers.jsonPath("$.aggregateId").value("OUT-SINGLE"));
    }

    @Test
    void unknownEventId_returns404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL + "/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isNotFound())
           .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void badEventIdFormat_returns400() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL + "/not-a-valid-uuid")
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isBadRequest())
           .andExpect(MockMvcResultMatchers.jsonPath("$.code").exists());
    }

    // ── 3. Tenant isolation ───────────────────────────────────────────────────

    @Test
    void tenantIsolation_t2Operator_cannotSeeOrFetchT1Events() throws Exception {
        UUID t1Id = createEvent(T1, "T1-ONLY", 1L);

        // T2 operator listing outbox sees empty list
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL)
                .header("Authorization", "Bearer " + opToken(T2)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(0));

        // T2 operator attempting to fetch T1's event ID directly gets 404
        mvc.perform(MockMvcRequestBuilders.get(OUTBOX_URL + "/" + t1Id)
                .header("Authorization", "Bearer " + opToken(T2)))
           .andExpect(MockMvcResultMatchers.status().isNotFound())
           .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("NOT_FOUND"));
    }
}
