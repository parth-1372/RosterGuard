package com.rosterguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY.
 * Seeds initial tenants and offices on startup so the docker-compose demo can be exercised
 * immediately without manual database setup.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private final JdbcTemplate jdbc;

    public DevDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String tenant : new String[] {"tenant-1", "tenant-2"}) {
            jdbc.update("""
                    INSERT INTO tenant (id, name) VALUES (?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, tenant, "Demo Tenant (" + tenant + ")");

            for (String office : new String[] {"BLR", "PUN", "HYD"}) {
                jdbc.update("""
                        INSERT INTO office (tenant_id, code, name) VALUES (?, ?, ?)
                        ON CONFLICT (tenant_id, code) DO NOTHING
                        """, tenant, office, "Office " + office);
            }
        }
        log.info("dev data seeded: tenants (tenant-1, tenant-2) and offices (BLR, PUN, HYD)");
    }
}

