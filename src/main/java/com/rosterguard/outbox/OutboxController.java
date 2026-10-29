package com.rosterguard.outbox;

import com.rosterguard.security.Caller;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator-only outbox monitoring endpoints. Tenant isolation is enforced via the JWT: the
 * operator's tenant is read from the authenticated principal, never from a request parameter.
 *
 * <ul>
 *   <li>GET /api/v1/ops/outbox          — list events (paged, optional status filter)</li>
 *   <li>GET /api/v1/ops/outbox/{id}     — single event detail</li>
 * </ul>
 *
 * Role enforcement is done entirely in {@code SecurityConfig}; no role check is duplicated here.
 */
@RestController
@RequestMapping("/api/v1/ops/outbox")
public class OutboxController {

    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;

    private final OutboxRepository repo;

    public OutboxController(OutboxRepository repo) {
        this.repo = repo;
    }

    /**
     * List outbox events for the caller's tenant. Supports keyset pagination via {@code after}
     * (the UUID of the last seen event from the previous page) and an optional {@code status}
     * filter (PENDING, PUBLISHED, FAILED).
     */
    @GetMapping
    public List<OutboxView> list(
            @RequestParam(required = false) String after,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String status,
            Authentication authentication) {

        Caller caller = Caller.from(authentication);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        return repo.list(caller.tenantId(), after, safeLimit, status)
                   .stream().map(OutboxView::from).toList();
    }

    @GetMapping("/{id}")
    public OutboxView get(@PathVariable String id, Authentication authentication) {
        Caller caller = Caller.from(authentication);
        UUID uuid = parseUuid(id);
        return repo.find(caller.tenantId(), uuid)
                   .map(OutboxView::from)
                   .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event ID");
        }
    }
}

