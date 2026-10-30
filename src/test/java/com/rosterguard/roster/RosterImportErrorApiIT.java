package com.rosterguard.roster;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.security.Role;
import com.rosterguard.security.TestTokenFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RosterImportErrorApiIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RosterImportService importService;

    private String opToken(String tenant) {
        return TestTokenFactory.validToken("op", tenant, Role.OPERATOR.name());
    }

    private String hrToken(String tenant) {
        return TestTokenFactory.validToken("hr", tenant, Role.HR_INTEGRATION.name());
    }

    private String taToken(String tenant) {
        return TestTokenFactory.validToken("ta", tenant, Role.TRANSPORT_ADMIN.name());
    }

    private UUID createImportWithErrors(String tenantId) throws Exception {
        String csv = """
                employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
                E-ERR-1,1,2024-01-15T08:00:00Z,Name 1,invalid-email,+919000000001,,BLR,DAY
                E-ERR-2,1,2024-01-15T08:00:00Z,Name 2,valid@test.com,badphone,,BLR,DAY
                E-ERR-3,1,2024-01-15T08:00:00Z,Name 3,valid@test.com,+919000000001,,UNKNOWN_OFFICE,DAY
                """;
        return importService.importCsv(tenantId,
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                Instant.parse("2024-01-15T12:00:00Z"));
    }

    // ── 1. Authentication & Authorization ────────────────────────────────────

    @Test
    void unauthenticated_errors_returns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/roster-imports/" + UUID.randomUUID() + "/errors"))
           .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void nonOperator_errors_returns403() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/roster-imports/" + id + "/errors")
                .header("Authorization", "Bearer " + hrToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isForbidden());

        mvc.perform(MockMvcRequestBuilders.get("/api/v1/roster-imports/" + id + "/errors")
                .header("Authorization", "Bearer " + taToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    // ── 2. Tenant Isolation & Not Found ───────────────────────────────────────

    @Test
    void tenantIsolation_t2OperatorCannotAccessT1Errors() throws Exception {
        UUID t1ImportId = createImportWithErrors(T1);

        // T2 operator requesting T1's import errors receives 404
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/roster-imports/" + t1ImportId + "/errors")
                .header("Authorization", "Bearer " + opToken(T2)))
           .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    // ── 3. Error Listing, Keyset Pagination, PII Safety ───────────────────────

    @Test
    void operator_listsErrors_withKeysetPaginationAndNoPii() throws Exception {
        UUID importId = createImportWithErrors(T1);

        // Page 1: limit 2
        String jsonPage1 = mvc.perform(MockMvcRequestBuilders.get("/api/v1/roster-imports/" + importId + "/errors")
                .param("limit", "2")
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(2))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].field").value("email"))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].code").value("INVALID_FORMAT"))
           // PII safety: raw invalid-email must not appear in response
           .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-email"))))
           .andExpect(MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("badphone"))))
           .andReturn().getResponse().getContentAsString();

        // Get last ID from page 1 to paginate
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonPage1);
        long lastId = root.get(1).get("id").asLong();

        // Page 2: after lastId
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/roster-imports/" + importId + "/errors")
                .param("after", String.valueOf(lastId))
                .param("limit", "2")
                .header("Authorization", "Bearer " + opToken(T1)))
           .andExpect(MockMvcResultMatchers.status().isOk())
           .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(1))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].field").value("officeCode"))
           .andExpect(MockMvcResultMatchers.jsonPath("$[0].code").value("UNKNOWN_OFFICE"));
    }
}

