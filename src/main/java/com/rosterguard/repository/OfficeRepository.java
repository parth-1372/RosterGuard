package com.rosterguard.repository;

import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfficeRepository {

    private final JdbcTemplate jdbc;

    public OfficeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> codes(String tenantId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT code FROM office WHERE tenant_id = ?", String.class, tenantId));
    }
}
