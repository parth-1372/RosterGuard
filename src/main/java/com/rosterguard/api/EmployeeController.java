package com.rosterguard.api;

import com.rosterguard.repository.EmployeeRecord;
import com.rosterguard.security.Caller;
import com.rosterguard.service.EmployeeNotFoundException;
import com.rosterguard.service.EmployeeQueryService;
import com.rosterguard.service.TransportFieldService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Employee reads and transport-field edits. Roles are enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    public record TransportRequest(String pickupPoint, String specialAssistance) {
    }

    public record ListResponse(List<EmployeeView> employees, String nextAfter) {
    }

    private final EmployeeQueryService queries;
    private final TransportFieldService transport;

    public EmployeeController(EmployeeQueryService queries, TransportFieldService transport) {
        this.queries = queries;
        this.transport = transport;
    }

    @GetMapping("/{employeeId}")
    public EmployeeView get(@PathVariable String employeeId, Authentication authentication) {
        Caller caller = Caller.from(authentication);
        // An EMPLOYEE may read only their own record. Anything else looks like "not found",
        // the same answer as for an ID that does not exist, so nothing is revealed.
        if (!caller.canReadAnyEmployee() && !employeeId.equals(caller.employeeId())) {
            throw new EmployeeNotFoundException(employeeId);
        }
        EmployeeRecord record = queries.get(caller.tenantId(), employeeId);
        return EmployeeView.from(record);
    }

    @GetMapping
    public ListResponse list(@RequestParam(required = false) String after,
                             @RequestParam(defaultValue = "50") int limit,
                             Authentication authentication) {
        Caller caller = Caller.from(authentication);
        List<EmployeeRecord> page = queries.list(caller.tenantId(), after, limit);
        int effectiveLimit = Math.max(1, Math.min(limit, EmployeeQueryService.MAX_PAGE_SIZE));
        String next = page.size() == effectiveLimit ? page.get(page.size() - 1).employeeId() : null;
        return new ListResponse(page.stream().map(EmployeeView::from).toList(), next);
    }

    @PutMapping("/{employeeId}/transport")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTransport(@PathVariable String employeeId, @RequestBody TransportRequest body,
                                Authentication authentication) {
        Caller caller = Caller.from(authentication);
        transport.update(caller.tenantId(), employeeId, body.pickupPoint(), body.specialAssistance());
    }
}

