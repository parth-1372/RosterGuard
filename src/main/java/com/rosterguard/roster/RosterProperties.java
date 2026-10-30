package com.rosterguard.roster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for roster import safety gates.
 *
 * @param missingThreshold the fraction of active employees absent from the snapshot
 *                         above which the import is held for operator approval (default 0.30).
 *                         Must be between 0.0 and 1.0.
 */
@ConfigurationProperties(prefix = "rosterguard.roster")
public record RosterProperties(
        @DefaultValue("0.30") double missingThreshold) {

    public RosterProperties {
        if (missingThreshold < 0.0 || missingThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "missingThreshold must be between 0.0 and 1.0, got: " + missingThreshold);
        }
    }
}

