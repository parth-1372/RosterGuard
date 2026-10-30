package com.rosterguard.roster;

import com.rosterguard.domain.FieldError;
import com.rosterguard.repository.OfficeRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates a nightly roster import end-to-end.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Create import record in STAGING state.</li>
 *   <li>Check tenant snapshot watermark (reject/hold older snapshots).</li>
 *   <li>Stream CSV: for each row, record seen IDs (even if invalid), stage valid rows, record errors.</li>
 *   <li>Safety gates — evaluated before any destructive write:
 *     <ul>
 *       <li>Invalid header row → NEEDS_REVIEW (INVALID_HEADER)</li>
 *       <li>Any row missing a usable employeeId → NEEDS_REVIEW (MISSING_EMPLOYEE_ID)</li>
 *       <li>Any duplicate employee ID in the file → NEEDS_REVIEW (DUPLICATE_EMPLOYEE_ID)</li>
 *       <li>snapshotGeneratedAt absent → AWAITING_APPROVAL (NO_SNAPSHOT_WATERMARK, no deactivations)</li>
 *       <li>Missing-employee ratio &ge; configurable threshold → AWAITING_APPROVAL (MISSING_THRESHOLD_EXCEEDED)</li>
 *     </ul>
 *   </li>
 *   <li>Apply upserts and outbox events in a single set-based transaction.</li>
 *   <li>Apply deactivations guarded by source_updated_at &le; snapshotGeneratedAt,
 *       cancelling future bookings, releasing seats, and enqueuing outbox events in the same transaction.</li>
 *   <li>Update tenant latest snapshot watermark on successful completion.</li>
 *   <li>Finalise the import record.</li>
 * </ol>
 *
 * <p>No PII is logged. Counters, status codes and IDs only.
 */
@Service
public class RosterImportService {

    private static final Logger log = LoggerFactory.getLogger(RosterImportService.class);

    private final RosterImportRepository imports;
    private final OfficeRepository offices;
    private final TransactionTemplate tx;
    private final RosterProperties props;

    public RosterImportService(RosterImportRepository imports, OfficeRepository offices,
                               TransactionTemplate tx, RosterProperties props) {
        this.imports = imports;
        this.offices = offices;
        this.tx = tx;
        this.props = props;
    }

    /**
     * Start and (synchronously) complete a roster import from the given CSV stream.
     *
     * @param tenantId            tenant scope (from JWT — never from request body)
     * @param csvStream           raw CSV bytes; caller is responsible for closing
     * @param snapshotGeneratedAt HR-supplied snapshot clock; null if not provided
     * @return the UUID of the created import record
     */
    public UUID importCsv(String tenantId, InputStream csvStream,
                          Instant snapshotGeneratedAt) throws IOException {

        // ── 1. Create import record ──────────────────────────────────────────
        UUID importId = imports.create(tenantId, snapshotGeneratedAt);
        log.info("import started tenant={} importId={} snapshotAt={}",
                tenantId, importId, snapshotGeneratedAt);

        try {
            return processImport(tenantId, importId, csvStream, snapshotGeneratedAt);
        } catch (IOException e) {
            imports.markFailed(tenantId, importId);
            log.error("import failed tenant={} importId={} cause={}", tenantId, importId,
                    e.getClass().getSimpleName());
            throw e;
        } catch (RuntimeException e) {
            imports.markFailed(tenantId, importId);
            log.error("import failed tenant={} importId={} cause={}", tenantId, importId,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    private UUID processImport(String tenantId, UUID importId, InputStream csvStream,
                               Instant snapshotGeneratedAt) throws IOException {

        int activeCountAtStart = imports.countActive(tenantId);

        // ── Stale snapshot watermark protection ───────────────────────────────
        if (snapshotGeneratedAt != null) {
            Optional<Instant> lastWatermark = imports.getLatestSnapshotWatermark(tenantId);
            if (lastWatermark.isPresent() && !snapshotGeneratedAt.isAfter(lastWatermark.get())) {
                log.warn("import needs review (stale snapshot) tenant={} importId={} snapshotAt={} watermark={}",
                        tenantId, importId, snapshotGeneratedAt, lastWatermark.get());
                finalise(tenantId, importId, ImportStatus.NEEDS_REVIEW,
                        activeCountAtStart, 0, 0,
                        0, 0, 0, HoldReason.STALE_SNAPSHOT);
                return importId;
            }
        }

        Set<String> knownOfficeCodes = offices.codes(tenantId);

        // ── 2. Stream and stage ──────────────────────────────────────────────
        List<CsvRow>  validBatch   = new ArrayList<>(RosterImportRepository.BATCH_SIZE);
        List<String>  seenIdBatch  = new ArrayList<>(RosterImportRepository.BATCH_SIZE);

        long[] counters = {0, 0, 0}; // [totalRows, rejectedRows, missingIdRows]
        boolean[] hasRowMissingId = {false};
        boolean[] hasHeaderError = {false};

        long totalRows = CsvParser.parse(
                csvStream,
                row -> {
                    counters[0]++;

                    // Record the employee ID in seen_id regardless of row validity.
                    if (row.employeeId() != null) {
                        seenIdBatch.add(row.employeeId());
                        if (seenIdBatch.size() >= RosterImportRepository.BATCH_SIZE) {
                            imports.recordSeenIds(tenantId, importId, seenIdBatch);
                            seenIdBatch.clear();
                        }
                    } else {
                        hasRowMissingId[0] = true;
                    }

                    if (row.isValid()) {
                        // Validate officeCode against the tenant's known offices.
                        if (!knownOfficeCodes.contains(row.profile().officeCode())) {
                            counters[1]++;
                            imports.recordErrors(tenantId, importId, row.rowNumber(), row.employeeId(),
                                    List.of(new FieldError("officeCode", "UNKNOWN_OFFICE")));
                            return;
                        }
                        validBatch.add(row);
                        if (validBatch.size() >= RosterImportRepository.BATCH_SIZE) {
                            imports.stageBatch(tenantId, importId, validBatch);
                            validBatch.clear();
                        }
                    } else {
                        counters[1]++;
                    }
                },
                (rowNum, errors) -> {
                    if (rowNum == 0) {
                        hasHeaderError[0] = true;
                    }
                    imports.recordErrors(tenantId, importId, rowNum, null, errors);
                });

        // Flush remaining rows.
        if (!seenIdBatch.isEmpty()) imports.recordSeenIds(tenantId, importId, seenIdBatch);
        if (!validBatch.isEmpty())  imports.stageBatch(tenantId, importId, validBatch);

        int rejectedRows = (int) counters[1];

        // ── 3. Safety gates ──────────────────────────────────────────────────

        // Gate 0: Header error
        if (hasHeaderError[0]) {
            log.warn("import needs review (invalid header) tenant={} importId={}", tenantId, importId);
            finalise(tenantId, importId, ImportStatus.NEEDS_REVIEW,
                    activeCountAtStart, (int) totalRows, rejectedRows,
                    0, 0, 0, HoldReason.INVALID_HEADER);
            return importId;
        }

        // Gate A: any row without a usable employee ID → data quality issue.
        if (hasRowMissingId[0]) {
            log.warn("import needs review (missing IDs) tenant={} importId={}", tenantId, importId);
            finalise(tenantId, importId, ImportStatus.NEEDS_REVIEW,
                    activeCountAtStart, (int) totalRows, rejectedRows,
                    0, 0, 0, HoldReason.MISSING_EMPLOYEE_ID);
            return importId;
        }

        // Gate B: any duplicated employee ID in the file → data quality issue.
        if (imports.hasDuplicateSeenIds(tenantId, importId)) {
            log.warn("import needs review (duplicate IDs) tenant={} importId={}", tenantId, importId);
            finalise(tenantId, importId, ImportStatus.NEEDS_REVIEW,
                    activeCountAtStart, (int) totalRows, rejectedRows,
                    0, 0, 0, HoldReason.DUPLICATE_EMPLOYEE_ID);
            return importId;
        }

        // Gate C: no snapshot watermark → cannot safely deactivate anyone.
        if (snapshotGeneratedAt == null) {
            int missing = imports.countMissing(tenantId, importId);
            int newCount = imports.countNew(tenantId, importId);
            int changed  = imports.countChanged(tenantId, importId);
            // Apply upserts only; skip deactivations.
            applyUpserts(tenantId, importId);
            log.warn("import awaiting approval (no watermark) tenant={} importId={}", tenantId, importId);
            finalise(tenantId, importId, ImportStatus.AWAITING_APPROVAL,
                    activeCountAtStart, (int) totalRows, rejectedRows,
                    newCount, changed, missing, HoldReason.NO_SNAPSHOT_WATERMARK);
            return importId;
        }

        // Gate D: missing threshold exceeded.
        int missing = imports.countMissing(tenantId, importId);
        if (activeCountAtStart > 0) {
            double missingRatio = (double) missing / activeCountAtStart;
            if (missingRatio >= props.missingThreshold()) {
                int newCount = imports.countNew(tenantId, importId);
                int changed  = imports.countChanged(tenantId, importId);
                applyUpserts(tenantId, importId); // upserts are safe regardless
                log.warn("import awaiting approval (threshold) tenant={} importId={} ratio={}",
                        tenantId, importId, String.format("%.2f", missingRatio));
                finalise(tenantId, importId, ImportStatus.AWAITING_APPROVAL,
                    activeCountAtStart, (int) totalRows, rejectedRows,
                    newCount, changed, missing, HoldReason.MISSING_THRESHOLD_EXCEEDED);
                return importId;
            }
        }

        // ── 4. Apply deactivations (if any missing) + upserts in a single transaction ──
        int newCount = imports.countNew(tenantId, importId);
        int changed  = imports.countChanged(tenantId, importId);
        int deactivated = tx.execute(status -> {
            int d = 0;
            if (missing > 0 && snapshotGeneratedAt != null) {
                d = imports.applyDeactivations(tenantId, importId, snapshotGeneratedAt);
            }
            applyUpserts(tenantId, importId);
            imports.updateLatestSnapshotWatermark(tenantId, snapshotGeneratedAt);
            return d;
        });

        log.info("import completed tenant={} importId={} new={} changed={} deactivated={}",
                tenantId, importId, newCount, changed, deactivated);

        finalise(tenantId, importId, ImportStatus.COMPLETED,
                activeCountAtStart, (int) totalRows, rejectedRows,
                newCount, changed, missing, null);
        return importId;
    }

    /**
     * Apply the approve decision atomically: transition status to COMPLETED first (to prevent
     * concurrent approval/rejection races), run deactivations, and update watermark in the same transaction.
     */
    public Optional<ImportRecord> approve(String tenantId, UUID importId, String operatorId) {
        return tx.execute(status -> {
            Optional<ImportRecord> rec = imports.find(tenantId, importId);
            if (rec.isEmpty() || rec.get().status() != ImportStatus.AWAITING_APPROVAL) {
                return Optional.empty();
            }
            ImportRecord r = rec.get();
            boolean approved = imports.approve(tenantId, importId, operatorId);
            if (!approved) {
                return Optional.empty();
            }
            // Re-run deactivations only if we have a watermark (NO_SNAPSHOT_WATERMARK holds skip them).
            if (r.snapshotGeneratedAt() != null
                    && !HoldReason.NO_SNAPSHOT_WATERMARK.name().equals(r.holdReason())) {
                imports.applyDeactivations(tenantId, importId, r.snapshotGeneratedAt());
            }
            imports.updateLatestSnapshotWatermark(tenantId, r.snapshotGeneratedAt());
            return imports.find(tenantId, importId);
        });
    }

    /**
     * Apply the reject decision atomically: transition status to REJECTED.
     */
    public Optional<ImportRecord> reject(String tenantId, UUID importId, String operatorId) {
        return tx.execute(status -> {
            Optional<ImportRecord> rec = imports.find(tenantId, importId);
            if (rec.isEmpty() || rec.get().status() != ImportStatus.AWAITING_APPROVAL) {
                return Optional.empty();
            }
            boolean rejected = imports.reject(tenantId, importId, operatorId);
            if (!rejected) {
                return Optional.empty();
            }
            return imports.find(tenantId, importId);
        });
    }

    public Optional<ImportRecord> findImport(String tenantId, UUID importId) {
        return imports.find(tenantId, importId);
    }

    public Optional<List<ImportErrorRecord>> listErrors(String tenantId, UUID importId, Long after, int limit) {
        if (imports.find(tenantId, importId).isEmpty()) {
            return Optional.empty();
        }
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return Optional.of(imports.listErrors(tenantId, importId, after, safeLimit));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyUpserts(String tenantId, UUID importId) {
        int applied = imports.applyUpserts(tenantId, importId);
        log.debug("upserts applied tenant={} importId={} count={}", tenantId, importId, applied);
    }

    private void finalise(String tenantId, UUID importId, ImportStatus status,
                          int activeCountAtStart, int totalRows, int rejectedRows,
                          int newCount, int changedCount, int missingCount,
                          HoldReason holdReason) {
        imports.finalise(tenantId, importId, status, activeCountAtStart, totalRows, rejectedRows,
                newCount, changedCount, missingCount, holdReason);
    }
}
