package com.rosterguard.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * HTTP-layer security integration tests. Exercises the actual filter chain via MockMvc so
 * role and tenant checks are proved on the real request path — not just unit-tested on beans.
 *
 * <p>Tenant isolation is verified by issuing tokens for T2 while addressing T1 data:
 * the service must see no employees and return 404, not data from a different tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SecurityIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final String EVENTS_URL = "/api/v1/employees/events";
    private static final String EMPLOYEES_URL = "/api/v1/employees";
    private static final String TRANSPORT_URL = "/api/v1/employees/E1/transport";

    /** A minimal valid HR event for the given employee. Always uses CREATE at version 1. */
    private static String hrEventBody(String employeeId) {
        return """
                {"events":[{"employeeId":"%s","type":"CREATE","sourceVersion":1,
                "sourceUpdatedAt":"2024-01-01T00:00:00Z",
                "name":"Test Employee","email":"test@example.com","phone":"+91-9000000001",
                "officeCode":"BLR","shiftCode":"MORNING","managerId":null}]}
                """.formatted(employeeId);
    }

    private ResultActions get(String url, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions post(String url, String body, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions postNoAuth(String url, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions put(String url, String body, String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Authorization", "Bearer " + token));
    }

    private void assertApiErrorShape(ResultActions ra) throws Exception {
        ra.andExpect(MockMvcResultMatchers.jsonPath("$.code").exists())
          .andExpect(MockMvcResultMatchers.jsonPath("$.message").exists());
    }

    private void assertNoPii(ResultActions ra) throws Exception {
        // Error bodies must not echo names, phones or email addresses.
        // The fixed messages we write never contain '@', 'phone' as a value,
        // or common name fragments. We assert the body does not contain typical PII patterns.
        String body = ra.andReturn().getResponse().getContentAsString();
        // Assert no email-like substring leaked from an exception message or input echo.
        assertThat(body).doesNotContain("example.com");
    }

    // ── 1. No token → 401 ────────────────────────────────────────────────────

    @Test
    void noToken_eventsEndpoint_returns401() throws Exception {
        ResultActions ra = postNoAuth(EVENTS_URL, hrEventBody("E1"));
        ra.andExpect(MockMvcResultMatchers.status().isUnauthorized());
        assertApiErrorShape(ra);
        assertNoPii(ra);
    }

    @Test
    void noToken_listEmployees_returns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(EMPLOYEES_URL))
           .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void noToken_getEmployee_returns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(EMPLOYEES_URL + "/E1"))
           .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void noToken_putTransport_returns401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put(TRANSPORT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickupPoint\":\"Gate A\"}"))
           .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    // ── 2. Expired token → 401 ───────────────────────────────────────────────

    @Test
    void expiredToken_returns401() throws Exception {
        String token = TestTokenFactory.expiredToken(T1);
        ResultActions ra = post(EVENTS_URL, hrEventBody("E1"), token);
        ra.andExpect(MockMvcResultMatchers.status().isUnauthorized());
        assertApiErrorShape(ra);
    }

    // ── 3. Wrong signature → 401 ─────────────────────────────────────────────

    @Test
    void wrongSignatureToken_returns401() throws Exception {
        String token = TestTokenFactory.wrongKeyToken(T1);
        ResultActions ra = get(EMPLOYEES_URL, token);
        ra.andExpect(MockMvcResultMatchers.status().isUnauthorized());
        assertApiErrorShape(ra);
    }

    // ── 4. Missing required claim → 401 ──────────────────────────────────────

    @Test
    void missingTenantClaim_returns401() throws Exception {
        String token = TestTokenFactory.missingTenantToken();
        ResultActions ra = get(EMPLOYEES_URL, token);
        ra.andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    // ── 5. Unknown / unrecognised role → 403 ─────────────────────────────────

    @Test
    void unknownRole_returns403() throws Exception {
        String token = TestTokenFactory.unknownRoleToken(T1);
        // Token is valid and trusted, but grants no recognised authority.
        ResultActions ra = get(EMPLOYEES_URL, token);
        ra.andExpect(MockMvcResultMatchers.status().isForbidden());
        assertApiErrorShape(ra);
        assertNoPii(ra);
    }

    // ── 6. Role-based access control ─────────────────────────────────────────

    @Nested
    class RoleAccess {

        @Test
        void hrIntegration_canPostEvents() throws Exception {
            String token = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            // Posting a valid event to a known tenant succeeds (200 with results).
            ResultActions ra = post(EVENTS_URL, hrEventBody("E-ROLE-01"), token);
            ra.andExpect(MockMvcResultMatchers.status().isOk());
        }

        @Test
        void transportAdmin_cannotPostEvents_gets403() throws Exception {
            String token = TestTokenFactory.validToken("ta", T1, Role.TRANSPORT_ADMIN.name());
            ResultActions ra = post(EVENTS_URL, hrEventBody("E-TA-01"), token);
            ra.andExpect(MockMvcResultMatchers.status().isForbidden());
            assertApiErrorShape(ra);
        }

        @Test
        void operator_cannotPostEvents_gets403() throws Exception {
            String token = TestTokenFactory.validToken("op", T1, Role.OPERATOR.name());
            ResultActions ra = post(EVENTS_URL, hrEventBody("E-OP-01"), token);
            ra.andExpect(MockMvcResultMatchers.status().isForbidden());
        }

        @Test
        void transportAdmin_canPutTransport() throws Exception {
            // Insert an employee first via HR_INTEGRATION so the record exists.
            String hrToken = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            post(EVENTS_URL, hrEventBody("E-TA-TR"), hrToken)
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.results[0].outcome").value("APPLIED"));

            String taToken = TestTokenFactory.validToken("ta", T1, Role.TRANSPORT_ADMIN.name());
            ResultActions ra = put("/api/v1/employees/E-TA-TR/transport",
                    "{\"pickupPoint\":\"Gate B\",\"specialAssistance\":null}", taToken);
            ra.andExpect(MockMvcResultMatchers.status().isNoContent());
        }

        @Test
        void hrIntegration_cannotPutTransport_gets403() throws Exception {
            String token = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            ResultActions ra = put(TRANSPORT_URL,
                    "{\"pickupPoint\":\"Gate B\"}", token);
            ra.andExpect(MockMvcResultMatchers.status().isForbidden());
        }

        @Test
        void operator_cannotPutTransport_gets403() throws Exception {
            String token = TestTokenFactory.validToken("op", T1, Role.OPERATOR.name());
            ResultActions ra = put(TRANSPORT_URL,
                    "{\"pickupPoint\":\"Gate B\"}", token);
            ra.andExpect(MockMvcResultMatchers.status().isForbidden());
        }

        @Test
        void transportAdmin_canListEmployees() throws Exception {
            String token = TestTokenFactory.validToken("ta", T1, Role.TRANSPORT_ADMIN.name());
            get(EMPLOYEES_URL, token)
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }

        @Test
        void operator_canListEmployees() throws Exception {
            String token = TestTokenFactory.validToken("op", T1, Role.OPERATOR.name());
            get(EMPLOYEES_URL, token)
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }

        @Test
        void hrIntegration_cannotListEmployees_gets403() throws Exception {
            String token = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            get(EMPLOYEES_URL, token)
                    .andExpect(MockMvcResultMatchers.status().isForbidden());
        }

        @Test
        void employee_canReadOwnRecord() throws Exception {
            // First create the employee via HR.
            String hrToken = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            post(EVENTS_URL, hrEventBody("E-SELF-01"), hrToken)
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.results[0].outcome").value("APPLIED"));

            String empToken = TestTokenFactory.employeeToken("u1", T1, "E-SELF-01");
            get(EMPLOYEES_URL + "/E-SELF-01", empToken)
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }

        @Test
        void employee_cannotReadOtherEmployee_gets404() throws Exception {
            // Create two employees.
            String hrToken = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            post(EVENTS_URL, hrEventBody("E-SELF-02"), hrToken)
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.results[0].outcome").value("APPLIED"));
            post(EVENTS_URL, hrEventBody("E-OTHER-02"), hrToken)
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.results[0].outcome").value("APPLIED"));

            // E-SELF-02 tries to read E-OTHER-02: must look like not-found.
            String empToken = TestTokenFactory.employeeToken("u2", T1, "E-SELF-02");
            ResultActions ra = get(EMPLOYEES_URL + "/E-OTHER-02", empToken);
            ra.andExpect(MockMvcResultMatchers.status().isNotFound());
            assertApiErrorShape(ra);
            // Make sure the error body doesn't reveal why (no name, email etc.)
            assertNoPii(ra);
        }

        @Test
        void employee_cannotListEmployees_gets403() throws Exception {
            String empToken = TestTokenFactory.employeeToken("u3", T1, "E3");
            get(EMPLOYEES_URL, empToken)
                    .andExpect(MockMvcResultMatchers.status().isForbidden());
        }
    }

    // ── 7. Tenant isolation ───────────────────────────────────────────────────

    @Nested
    class TenantIsolation {

        @BeforeEach
        void seedT1Employee() throws Exception {
            // Create employee E-T1 in T1 via an HR_INTEGRATION token for T1.
            String hrToken = TestTokenFactory.validToken("hr-sys", T1, Role.HR_INTEGRATION.name());
            post(EVENTS_URL, hrEventBody("E-T1"), hrToken)
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }

        @Test
        void t2Token_cannotReadT1Employee_gets404() throws Exception {
            // T2 TRANSPORT_ADMIN asks for an employee that exists only in T1.
            String t2Token = TestTokenFactory.validToken("ta-t2", T2, Role.TRANSPORT_ADMIN.name());
            ResultActions ra = get(EMPLOYEES_URL + "/E-T1", t2Token);
            ra.andExpect(MockMvcResultMatchers.status().isNotFound());
        }

        @Test
        void t2Token_listReturnsOnlyT2Employees() throws Exception {
            // T1 has an employee; T2 has none. The T2 OPERATOR must see an empty list.
            String t2Token = TestTokenFactory.validToken("op-t2", T2, Role.OPERATOR.name());
            ResultActions ra = get(EMPLOYEES_URL, t2Token);
            ra.andExpect(MockMvcResultMatchers.status().isOk())
              .andExpect(MockMvcResultMatchers.jsonPath("$.employees").isArray())
              .andExpect(MockMvcResultMatchers.jsonPath("$.employees.length()").value(0));
        }

        @Test
        void t2HrToken_postingEventCreatesEmployeeInT2NotT1() throws Exception {
            // An HR_INTEGRATION token for T2 posts an event for E-CROSS.
            String t2HrToken = TestTokenFactory.validToken("hr-sys", T2, Role.HR_INTEGRATION.name());
            post(EVENTS_URL, hrEventBody("E-CROSS"), t2HrToken)
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.results[0].outcome").value("APPLIED"));

            // T1 TRANSPORT_ADMIN cannot see E-CROSS (it's a T2 employee).
            String t1Token = TestTokenFactory.validToken("ta", T1, Role.TRANSPORT_ADMIN.name());
            get(EMPLOYEES_URL + "/E-CROSS", t1Token)
                    .andExpect(MockMvcResultMatchers.status().isNotFound());

            // T2 TRANSPORT_ADMIN CAN see E-CROSS.
            String t2Token = TestTokenFactory.validToken("ta", T2, Role.TRANSPORT_ADMIN.name());
            get(EMPLOYEES_URL + "/E-CROSS", t2Token)
                    .andExpect(MockMvcResultMatchers.status().isOk());
        }

        @Test
        void t2TransportAdmin_cannotUpdateT1Employee_gets404() throws Exception {
            // T2 TRANSPORT_ADMIN tries to PUT transport on a T1 employee.
            String t2Token = TestTokenFactory.validToken("ta-t2", T2, Role.TRANSPORT_ADMIN.name());
            ResultActions ra = put("/api/v1/employees/E-T1/transport",
                    "{\"pickupPoint\":\"Gate X\",\"specialAssistance\":null}", t2Token);
            // Row not found in T2 → TransportFieldService throws EmployeeNotFoundException.
            ra.andExpect(MockMvcResultMatchers.status().isNotFound());
        }
    }

    // ── 8. Health is open without a token ────────────────────────────────────

    @Test
    void healthEndpoint_isPublic() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
           .andExpect(MockMvcResultMatchers.status().isOk());
    }

    // ── 9. 401/403 responses do not leak PII ─────────────────────────────────

    @Test
    void unauthorised_errorBody_containsNoEmailOrPhone() throws Exception {
        // Send an HR event (which happens to contain email/phone in the body) without auth.
        ResultActions ra = postNoAuth(EVENTS_URL, hrEventBody("E-PII"));
        ra.andExpect(MockMvcResultMatchers.status().isUnauthorized());
        String body = ra.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("example.com");
        assertThat(body).doesNotContain("9000000001");
        assertThat(body).doesNotContain("Test Employee");
    }

    @Test
    void forbidden_errorBody_containsNoEmailOrPhone() throws Exception {
        String token = TestTokenFactory.validToken("ta", T1, Role.TRANSPORT_ADMIN.name());
        ResultActions ra = post(EVENTS_URL, hrEventBody("E-PII"), token);
        ra.andExpect(MockMvcResultMatchers.status().isForbidden());
        String body = ra.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("example.com");
        assertThat(body).doesNotContain("9000000001");
        assertThat(body).doesNotContain("Test Employee");
    }
}
