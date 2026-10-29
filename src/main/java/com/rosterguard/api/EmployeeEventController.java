package com.rosterguard.api;

import com.rosterguard.domain.FieldError;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.security.Caller;
import com.rosterguard.service.EmployeeSyncService;
import com.rosterguard.service.EventResult;
import com.rosterguard.service.InvalidRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HR event ingestion. Restricted to HR_INTEGRATION in SecurityConfig. HTTP concerns only. */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeEventController {

    static final int MAX_BATCH_SIZE = 1000;

    public record BatchRequest(List<EventDto> events) {
    }

    public record BatchResponse(List<EventResult> results) {
    }

    private final EmployeeSyncService sync;

    public EmployeeEventController(EmployeeSyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/events")
    public BatchResponse ingest(@RequestBody BatchRequest request, Authentication authentication) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new InvalidRequestException(List.of(new FieldError("events", "REQUIRED")));
        }
        if (request.events().size() > MAX_BATCH_SIZE) {
            throw new InvalidRequestException(List.of(new FieldError("events", "BATCH_TOO_LARGE")));
        }
        String tenantId = Caller.from(authentication).tenantId();

        // Records that cannot even be parsed are rejected individually; the rest go to the service.
        EventResult[] results = new EventResult[request.events().size()];
        List<HrEvent> parseable = new ArrayList<>();
        List<Integer> parseablePositions = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            EventDto dto = request.events().get(i);
            if (dto == null) {
                results[i] = EventResult.rejected(null, List.of(new FieldError("event", "REQUIRED")));
                continue;
            }
            EventDto.Parsed parsed = dto.toDomain();
            if (parsed.errors().isEmpty()) {
                parseable.add(parsed.event());
                parseablePositions.add(i);
            } else {
                results[i] = EventResult.rejected(dto.employeeId(), parsed.errors());
            }
        }
        List<EventResult> processed = sync.processBatch(tenantId, parseable);
        for (int j = 0; j < processed.size(); j++) {
            results[parseablePositions.get(j)] = processed.get(j);
        }
        return new BatchResponse(List.of(results));
    }
}

