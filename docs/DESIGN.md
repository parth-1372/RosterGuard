# RosterGuard — System Design & Architecture

Enterprise-grade architecture documentation for RosterGuard: a high-throughput, multi-tenant employee roster synchronization service designed for zero race conditions, defensive reconciliation, and guaranteed side-effect execution.

---

## 1. System Architecture

```mermaid
flowchart TD
    subgraph Ingestion["Ingestion Layer"]
        HR_EVT["HR Real-Time Events<br/>(POST /api/v1/employees/events)"]
        CSV_UP["Nightly CSV Snapshots<br/>(POST /api/v1/roster-imports)"]
        TA_REQ["Transport Admin Updates<br/>(PUT /api/v1/employees/{id}/transport)"]
    end

    subgraph Auth["Security & Tenant Boundary"]
        JWT["Stateless JWT Filter<br/>(RFC 7519, HS256, Mandatory Tenant & Roles)"]
    end

    subgraph Engine["Processing Engine & Safety Gates"]
        VP["Version Policy & Canonical Hasher<br/>(SHA-256 Length-Prefixed)"]
        PARSER["Streaming CSV Parser<br/>(Dynamic Header Mapping, O(1) Heap)"]
        GATES{"Safety Gates & Tripwires<br/>1. Header/Row Syntax?<br/>2. Duplicate IDs?<br/>3. Stale Snapshot Watermark?<br/>4. Missing Ratio >= 30%?"}
    end

    subgraph Storage["PostgreSQL 16 Engine (Composite Tenant Keys)"]
        EMP[("employee<br/>(tenant_id, employee_id)")]
        REV[("employee_event_review<br/>(Split-Brain Conflicts)")]
        STG[("roster_staging & import_seen_id")]
        ERR[("import_row_error<br/>(PII-Free Field Codes)")]
        BOOK[("booking & seat_allocation")]
        OUTBOX[("outbox_event<br/>(All Applied Mutations)")]
        TENANT[("tenant<br/>(latest_snapshot_watermark)")]
    end

    subgraph Outbox["Transactional Outbox Relay"]
        RELAY["Polling Worker Pool<br/>(FOR UPDATE SKIP LOCKED)"]
        LEASE["CTE Atomic Lease Extension<br/>(next_attempt_at = now() + 60s)"]
        PUB["External Publisher<br/>(At-Least-Once Delivery)"]
    end

    HR_EVT --> JWT
    CSV_UP --> JWT
    TA_REQ --> JWT

    JWT --> VP
    JWT --> PARSER

    VP -->|Conditional Atomic Upsert| EMP
    VP -->|Split-Brain Conflict| REV
    VP -->|Event Ingestion Outbox| OUTBOX

    PARSER --> ERR
    PARSER --> STG
    STG --> GATES

    GATES -->|Reconciliation Safe| EMP
    GATES -->|Exceeds Threshold| STG

    EMP -->|Atomic Deactivations (Writable CTE)| BOOK
    EMP -->|Bulk Upserts & Deactivations| OUTBOX
    EMP -->|Watermark Update| TENANT

    TA_REQ -->|Transport Mutation & Outbox| EMP
    TA_REQ --> OUTBOX

    OUTBOX --> RELAY
    RELAY --> LEASE
    LEASE --> PUB
```

---

## 2. Core Data Flows

### A. Real-Time HR Event Ingestion
1. **JWT Verification:** Authenticates `HR_INTEGRATION` role and extracts `tenant_id`.
2. **Canonical Hashing:** Computes deterministic SHA-256 payload hash (`CanonicalHasher`).
3. **Atomic Upsert:**
   ```sql
   INSERT INTO employee (tenant_id, employee_id, ..., source_version, payload_hash)
   VALUES (?, ?, ..., ?, ?)
   ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
       name = EXCLUDED.name,
       source_version = EXCLUDED.source_version,
       payload_hash = EXCLUDED.payload_hash,
       applied_at = now()
   WHERE employee.source_version < EXCLUDED.source_version
   RETURNING (xmax = 0) AS is_new;
   ```
4. **Outcome Classification:**
   - Version newer $\rightarrow$ **Applied**. Produces `EMPLOYEE_CREATED` or `EMPLOYEE_UPDATED` in `outbox_event` within the same transaction.
   - Version older $\rightarrow$ **Stale**. Ignored cleanly.
   - Same version + identical payload hash $\rightarrow$ **Duplicate**. Idempotent no-op.
   - Same version + conflicting payload hash $\rightarrow$ **Conflict Held**. Inserted into `employee_event_review` for human review.

### B. Defensive CSV Streaming & Safety Gates
1. **Streaming Ingestion:** `CsvParser` processes CSV row-by-row with dynamic column index mapping. Emits batches of 1,000 into unlogged staging tables (`roster_staging`, `import_seen_id`). JVM heap remains constant ($O(1)$).
2. **Seen ID Capture:** Every valid or invalid line containing a parseable ID increments `import_seen_id`. Profile formatting errors never cause an employee to be marked as missing.
3. **Safety Tripwires (Zero Destructive Deactivation Without Human Review):**
   - **Header Syntax:** Missing required columns, duplicates, or unexpected columns halt parsing at row 0 (`NEEDS_REVIEW`).
   - **Missing Employee IDs:** Rows without usable IDs trigger `NEEDS_REVIEW`.
   - **Duplicate IDs:** Duplicate occurrences in `import_seen_id` trigger `NEEDS_REVIEW`.
   - **Stale Snapshot Watermark:** If `snapshotGeneratedAt <= tenant.latest_snapshot_watermark`, import halts as `NEEDS_REVIEW` (`HoldReason.STALE_SNAPSHOT`).
   - **Missing Threshold Exceeded:** If absent employees $\ge$ configured threshold (default 30%), import is held as `AWAITING_APPROVAL`.
4. **Set-Based Writable CTE Reconciliation:**
   When verified, employee upserts, booking cancellations, seat releases, and outbox event insertions execute concurrently in PostgreSQL writable CTEs in one transaction.

### C. Transport Field Updates & Separation of Concerns
1. **Strict Field Ownership:** Transport Admin API updates only `pickup_point` and `special_assistance`.
2. **No HR Version Interference:** Operates on an independent `transport_version` counter incremented by PostgreSQL. Cannot overwrite HR profile fields or alter HR `source_version`.
3. **Downstream Propagation:** Enqueues `EMPLOYEE_TRANSPORT_UPDATED` outbox events so routing and dispatch services recompute vehicle allocation immediately.

### D. Transactional Outbox Relay & Worker Leasing
1. **Atomic Lease Acquisition:**
   ```sql
   WITH claimed AS (
       SELECT tenant_id, id FROM outbox_event
       WHERE status = 'PENDING' AND next_attempt_at <= now()
       ORDER BY next_attempt_at LIMIT 50
       FOR UPDATE SKIP LOCKED
   )
   UPDATE outbox_event e
   SET next_attempt_at = now() + interval '60 seconds'
   FROM claimed
   WHERE e.tenant_id = claimed.tenant_id AND e.id = claimed.id
   RETURNING ...;
   ```
2. **Publish & Commit:** Events are published via `EventPublisher`. On success, marked `PUBLISHED`. On failure, retried with exponential backoff (`base * 2^attempts`) up to 5 attempts before dead-lettering (`FAILED`).
3. **Crash Recovery:** If a worker crashes mid-flight, the 60s lease naturally expires, allowing surviving workers to re-claim and publish (**at least once delivery**).

---

## 3. Database Schema & Multi-Tenant Isolation

- Every table begins with `tenant_id VARCHAR(64)` as the first column in primary and foreign keys.
- Physical isolation is enforced by the PostgreSQL query planner and schema constraints:
  - `employee`: `(tenant_id, employee_id)`
  - `booking`: `(tenant_id, id)` $\rightarrow$ FK `(tenant_id, employee_id)`
  - `seat_allocation`: `(tenant_id, booking_id)` $\rightarrow$ FK `(tenant_id, booking_id)`
  - `outbox_event`: `(tenant_id, id)` $\rightarrow$ Unique `(tenant_id, dedup_key)`
  - `roster_import`: `(tenant_id, id)`
  - `import_seen_id`: `(tenant_id, import_id, employee_id)`
  - `import_row_error`: `(tenant_id, id)`

---

## 4. Key Architectural Decisions & Invariants

1. **Unified Source Version Contract (D1):**
   - Both real-time API events and CSV rows share a single monotonically increasing integer `sourceVersion`.
   - Never synthesize versions (e.g. `stored + 1`); an old snapshot must never supersede newer events.
2. **HR Source Watermarks vs. Server Time (D1b):**
   - Server clock (`applied_at`) is strictly for auditing. Old-file protection evaluates `employee.source_updated_at <= import.snapshot_generated_at` (both originating from the upstream HR source system clock).
   - If an employee or snapshot lacks a source watermark, deactivation is flagged as ambiguous and held (`NEEDS_REVIEW`).
3. **Strict Field Ownership Boundaries (D4, D5, D11):**
   - **HR System:** Owns `name`, `email`, `phone` (validated E.164), `managerId`, `officeCode`, and `shiftCode`.
   - **Transport Admin:** Owns `pickupPoint` and `specialAssistance`.
   - HR updates never overwrite transport fields; transport updates never alter HR `source_version`.
4. **Deterministic Canonical Hashing (D9):**
   - SHA-256 lowercase hex hashing with 4-byte length-prefixing on UTF-8 bytes prevents boundary collision bugs (e.g. `("ab","c")` vs `("a","bc")`).
   - `ROSTER_V1` hash covers HR-owned fields (excluding versions and transport fields) to detect meaningful changes during CSV diffing.
   - `EVENT_V1` hash includes event type to detect identical duplicate deliveries vs. split-brain same-version conflicts.
5. **PostgreSQL JDBC Batch Conflict Resolution (D13):**
   - Configured with `reWriteBatchedInserts=true` to combine multi-row statements.
   - In-memory pre-aggregation in `RosterImportRepository` eliminates PostgreSQL `ON CONFLICT DO UPDATE command cannot affect row a second time` errors when duplicate IDs appear within the same batch.


