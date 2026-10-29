package com.rosterguard.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default publisher: logs the event ID, type and aggregate ID at INFO level.
 * No PII is logged — payload is omitted; aggregateId is an employee ID (internal key only).
 *
 * <p>Swap this bean for a Kafka or HTTP publisher without touching any other class.
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("event published tenant={} id={} type={} aggregate={}",
                event.tenantId(), event.id(), event.eventType(), event.aggregateId());
    }
}

