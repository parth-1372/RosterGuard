package com.rosterguard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rosterguard.domain.CanonicalHasher;
import com.rosterguard.domain.FieldError;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrEventValidator;
import com.rosterguard.domain.VersionDecision;
import com.rosterguard.domain.VersionPolicy;
import com.rosterguard.outbox.OutboxRepository;
import com.rosterguard.repository.EmployeeRepository;
import com.rosterguard.repository.EmployeeRepository.UpsertResult;
import com.rosterguard.repository.EmployeeRepository.VersionInfo;
import com.rosterguard.repository.OfficeRepository;
import com.rosterguard.service.EventResult.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies HR events. Each event is validated and applied in its own transaction, so one bad or
 * failing event never affects the others in a batch. Logs contain tenant, employee ID and
 * version only, never personal data.
 */
@Service
public class EmployeeSyncService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeSyncService.class);

    private final EmployeeRepository employees;
    private final OfficeRepository offices;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public EmployeeSyncService(EmployeeRepository employees, OfficeRepository offices,
                               OutboxRepository outbox,
                               TransactionTemplate tx, ObjectMapper mapper) {
        this.employees = employees;
        this.offices = offices;
        this.outbox = outbox;
        this.tx = tx;
        this.mapper = mapper;
    }

    public List<EventResult> processBatch(String tenantId, List<HrEvent> events) {
        Set<String> officeCodes = offices.codes(tenantId);
        List<EventResult> results = new ArrayList<>(events.size());
        for (HrEvent event : events) {
            results.add(processOne(tenantId, event, officeCodes));
        }
        return results;
    }

    public EventResult process(String tenantId, HrEvent event) {
        return processOne(tenantId, event, offices.codes(tenantId));
    }

    private EventResult processOne(String tenantId, HrEvent event, Set<String> officeCodes) {
        List<FieldError> errors = HrEventValidator.validate(event, officeCodes);
        if (!errors.isEmpty()) {
            return EventResult.rejected(event.employeeId(), errors);
        }
        try {
            return tx.execute(status -> apply(tenantId, event));
        } catch (RuntimeException e) {
            // Log the exception type only: messages from the driver could echo SQL parameters.
            log.error("Event failed tenant={} employee={} version={} cause={}",
                    tenantId, event.employeeId(), event.sourceVersion(), e.getClass().getSimpleName());
            return EventResult.of(event.employeeId(), Outcome.ERROR);
        }
    }

    private EventResult apply(String tenantId, HrEvent event) {
        String id = event.employeeId();
        long version = event.sourceVersion();
        String payloadHash = CanonicalHasher.payloadHash(event.type(), event.effectiveProfile());

        if (event.type() == HrEventType.DEACTIVATE) {
            boolean applied = employees.deactivateWithSideEffects(
                    tenantId, id, version, event.sourceUpdatedAt(), payloadHash);
            if (applied) {
                return EventResult.of(id, Outcome.APPLIED);
            }
        } else {
            UpsertResult res = employees.upsertHrWithResult(tenantId, id, version, event.sourceUpdatedAt(),
                    event.effectiveProfile(),
                    CanonicalHasher.rosterHash(event.effectiveProfile()), payloadHash);
            if (res.applied()) {
                if (res.isNew()) {
                    outbox.insertCreateEvent(tenantId, id, version, event.effectiveProfile());
                } else {
                    outbox.insertUpdateEvent(tenantId, id, version, event.effectiveProfile());
                }
                return EventResult.of(id, Outcome.APPLIED);
            }
        }

        // Not applied: either the employee does not exist (deactivate) or stored version >= incoming.
        VersionInfo stored = employees.findVersionInfo(tenantId, id).orElse(null);
        if (stored == null) {
            return EventResult.rejected(id, List.of(new FieldError("employeeId", "UNKNOWN_EMPLOYEE")));
        }
        VersionDecision decision = VersionPolicy.decide(
                stored.sourceVersion(), stored.payloadHash(), version, payloadHash);
        return switch (decision) {
            case DUPLICATE -> EventResult.of(id, Outcome.DUPLICATE);
            case STALE -> EventResult.of(id, Outcome.STALE);
            case CONFLICT -> {
                employees.saveConflictForReview(tenantId, id, event.type(), version,
                        stored.payloadHash(), payloadHash, toJson(event));
                log.warn("Same-version conflict held for review tenant={} employee={} version={}",
                        tenantId, id, version);
                yield EventResult.of(id, Outcome.CONFLICT_HELD);
            }
            // The write is atomic on "stored < incoming", so this cannot happen.
            case APPLY -> throw new IllegalStateException("Write rejected but policy says APPLY");
        };
    }

    private String toJson(HrEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event for review", e);
        }
    }
}
