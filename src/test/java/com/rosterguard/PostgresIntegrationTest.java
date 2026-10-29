package com.rosterguard;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared real-PostgreSQL base. One container per test JVM (stopped by Testcontainers' Ryuk at
 * exit). Each test starts from empty tables with two tenants and known offices, all synthetic.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    protected static final String T1 = "tenant-1";
    protected static final String T2 = "tenant-2";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE tenant CASCADE");
        for (String tenant : new String[] {T1, T2}) {
            jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenant, "Synthetic " + tenant);
            for (String office : new String[] {"BLR", "PUN"}) {
                jdbc.update("INSERT INTO office (tenant_id, code, name) VALUES (?, ?, ?)",
                        tenant, office, "Office " + office);
            }
        }
    }
}
