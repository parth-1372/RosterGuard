package com.rosterguard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves the app boots against real PostgreSQL, Flyway migrates, and /health reports UP. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayCreatedBaselineTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);
        assertThat(tables).contains("tenant", "office", "employee");
    }

    @Test
    void employeeVersionCannotBeNegative() {
        jdbc.update("INSERT INTO tenant (id, name) VALUES ('t1', 'Tenant One')");
        try {
            jdbc.update("INSERT INTO employee (tenant_id, employee_id, source_version, row_hash) "
                    + "VALUES ('t1', 'E1', -1, 'x')");
            throw new AssertionError("negative version should be rejected by the CHECK constraint");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // expected: database enforces the invariant, not just application code
        }
    }
}

