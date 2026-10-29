package com.rosterguard.roster;

import com.rosterguard.domain.FieldError;
import com.rosterguard.security.Caller;
import com.rosterguard.service.InvalidRequestException;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Roster CSV import endpoints. Role enforcement is done entirely in SecurityConfig;
 * no role checks are duplicated here.
 *
 * <ul>
 *   <li>POST   /api/v1/roster-imports            — HR_INTEGRATION: upload CSV</li>
 *   <li>GET    /api/v1/roster-imports/{id}        — OPERATOR: get status</li>
 *   <li>POST   /api/v1/roster-imports/{id}/approve — OPERATOR: approve held import</li>
 *   <li>POST   /api/v1/roster-imports/{id}/reject  — OPERATOR: reject held import</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/roster-imports")
public class RosterImportController {

    private final RosterImportService service;

    public RosterImportController(RosterImportService service) {
        this.service = service;
    }

    /**
     * Upload a nightly CSV file. The optional {@code snapshotGeneratedAt} parameter is the
     * HR-system clock at which the snapshot was generated; used for old-file protection.
     * If absent, deactivations are held for operator review.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportView upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "snapshotGeneratedAt", required = false) String snapshotAt,
            Authentication authentication) throws IOException {

        Caller caller = Caller.from(authentication);

        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException(List.of(new FieldError("file", "REQUIRED")));
        }

        Instant snapshotInstant = null;
        if (snapshotAt != null && !snapshotAt.isBlank()) {
            try {
                snapshotInstant = Instant.parse(snapshotAt.trim());
            } catch (DateTimeParseException e) {
                throw new InvalidRequestException(
                        List.of(new FieldError("snapshotGeneratedAt", "INVALID_FORMAT")));
            }
        }

        UUID importId = service.importCsv(caller.tenantId(), file.getInputStream(), snapshotInstant);
        return service.findImport(caller.tenantId(), importId)
                .map(ImportView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    @GetMapping("/{importId}")
    public ImportView get(@PathVariable String importId, Authentication authentication) {
        Caller caller = Caller.from(authentication);
        UUID id = parseUuid(importId);
        return service.findImport(caller.tenantId(), id)
                .map(ImportView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/{importId}/approve")
    public ImportView approve(@PathVariable String importId, Authentication authentication) {
        Caller caller = Caller.from(authentication);
        UUID id = parseUuid(importId);
        return service.approve(caller.tenantId(), id, caller.userId())
                .map(ImportView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/{importId}/reject")
    public ImportView reject(@PathVariable String importId, Authentication authentication) {
        Caller caller = Caller.from(authentication);
        UUID id = parseUuid(importId);
        return service.reject(caller.tenantId(), id, caller.userId())
                .map(ImportView::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid import ID");
        }
    }
}

