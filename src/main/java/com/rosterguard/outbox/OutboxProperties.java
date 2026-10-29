package com.rosterguard.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox relay configuration. All properties are prefixed with {@code rosterguard.outbox}.
 *
 * @param relayIntervalMs polling interval in milliseconds (default 5 000)
 * @param maxAttempts     maximum publish attempts before dead-lettering (default 5)
 * @param leaseSeconds    lease duration in seconds to guard claimed events against concurrent workers (default 60)
 */
@ConfigurationProperties(prefix = "rosterguard.outbox")
public record OutboxProperties(
        @DefaultValue("5000") long relayIntervalMs,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("60") int leaseSeconds) {
}
