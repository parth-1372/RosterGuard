# RosterGuard — Reliable Employee Roster Synchronisation Service

> **MoveInSync SDE Intern Case Study Implementation**  
> An enterprise-grade, multi-tenant employee roster synchronisation service built with **Java 21**, **Spring Boot 3.4.5**, and **PostgreSQL 16**.  
> Bridges asynchronous real-time HR event streams and nightly full-roster CSV reconciliation without race conditions, data loss, or unsafe deactivations.

---

## 1. System Architecture

```mermaid
flowchart TD
    subgraph Ingestion["Ingestion Layer"]
        HR_API["HR Event Stream<br/>(POST /api/v1/employees/events)"]
        CSV_IN["Nightly CSV Snapshot<br/>(POST /api/v1/roster/imports)"]
        TA_API["Transport Admin API<br/>(PUT /api/v1/employees/{id}/transport)"]
    end

    subgraph Security["Stateless JWT Security & Tenant Boundary"]
        JWT_AUTH["Token Validator<br/>(Extracts tenant & roles, HS256)"]
    end

    subgraph Core["Core Processing & Safety Gates"]
        VP["Version Policy & Canonical Hasher<br/>(SHA-256 Length-Prefixed)"]
        STREAM["Streaming CSV Parser<br/>(Batch size: 1,000, O(1) Heap)"]
        GATES{"Safety Gates<br/>- Duplicate IDs?<br/>- Missing IDs?<br/>- Missing Ratio >= 30%?<br/>- Snapshot Watermark?"}
    end

    subgraph DB["PostgreSQL 16 Engine (Tenant-Scoped Keys)"]
        EMP[("employee<br/>(tenant_id, employee_id)")]
        REV[("employee_event_review<br/>(Conflicting Payloads)")]
        STG[("roster_staging & import_seen_id")]
        BOOK[("booking & seat_allocation")]
        OUTBOX[("outbox_event<br/>(Transactional Outbox)")]
    end

    subgraph Relay["Transactional Outbox Relay"]
        WORKER["Outbox Polling Worker<br/>(SKIP LOCKED + CTE Atomic Lease)"]
        PUBLISHER["Event Publisher<br/>(At-Least-Once Delivery)"]
    end

    HR_API --> JWT_AUTH
    CSV_IN --> JWT_AUTH
    TA_API --> JWT_AUTH

    JWT_AUTH --> VP
    JWT_AUTH --> STREAM

    VP -->|Conditional Atomic Upsert| EMP
    VP -->|Split-Brain Conflict| REV

    STREAM --> STG
    STG --> GATES
    GATES -->|Safety Breached| STG
    GATES -->|Reconciliation Safe| EMP

    EMP -->|Transactional Deactivation| BOOK
    EMP -->|Transactional Event Insertion| OUTBOX

    OUTBOX --> WORKER
    WORKER --> PUBLISHER
```

---

## 2. Core Architectural Principles & Invariants

1. **Unified Version Monotonicity Without Locks (D1/D1b):**
   - All real-time API events and CSV rows share a single monotonically increasing integer `sourceVersion` and timestamp `sourceUpdatedAt` provided by the authoritative HR system.
   - State updates execute via a single conditional atomic SQL statement:
     ```sql
     INSERT INTO employee (tenant_id, employee_id, ..., source_version)
     VALUES (?, ?, ..., ?)
     ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
         name = EXCLUDED.name,
         source_version = EXCLUDED.source_version,
         ...
     WHERE employee.source_version < EXCLUDED.source_version;
     ```
   - Concurrent updates serialize at the row level without explicit table locks or distributed locks.
2. **Defensive CSV Reconciliation & $O(1)$ Memory (D3):**
   - CSV processing streams row-by-row into PostgreSQL unlogged staging tables in batches of 1,000. JVM heap utilization remains flat ($O(1)$) regardless of file size.
   - **Seen ID Tracking:** Every parseable employee ID is recorded in `import_seen_id` before field validation. A syntax error in a profile does not mark an employee as missing.
   - **Destructive Deactivation Safety Gates:** If duplicate employee IDs exist, any row has a missing ID, or absent employees exceed the 30% safety threshold, reconciliation halts in `NEEDS_REVIEW` or `AWAITING_APPROVAL`. Zero deactivations occur without manual human sign-off.
   - **Source Watermark Protection:** Absent employees are only deactivated if `employee.source_updated_at <= snapshot_generated_at`. An old snapshot cannot deactivate an employee modified after the snapshot was taken.
3. **Physical Multi-Tenant Isolation (D10):**
   - Every database table begins with `tenant_id` as the primary key prefix.
   - Foreign keys to `employee`, `roster_import`, and `booking` are composite keys including `tenant_id`, enforced physically by the PostgreSQL engine.
   - Tenant context is extracted exclusively from the authenticated JWT `tenant` claim, never from request parameters.
4. **Transactional Outbox & Side Effect Guarantee (D12):**
   - Deactivation (via HR event or CSV import), future booking cancellation (`booking.status = 'CANCELLED'`), seat release (`seat_allocation.released_at = now()`), and outbox event insertion (`outbox_event`) execute in a single PostgreSQL transaction.
   - The outbox relay uses `SELECT ... FOR UPDATE SKIP LOCKED` combined with an atomic CTE lease renewal (`next_attempt_at = now() + interval '60s'`) to prevent concurrent workers from claiming the same event.
   - Guarantees **at least once** delivery. Downstream consumers deduplicate using `id` or `dedup_key`.

---

## 3. Empirically Verified Benchmarks (Milestone 6)

All benchmarks were measured on Windows 11 AMD64 (32 logical cores) using JDK 21.0.13 and PostgreSQL 16 via Testcontainers.

### Benchmark 1: 200,000-Row CSV Reconciliation (`CsvReconciliationBenchmark`)
Target: Completion within 60 seconds and capped heap memory $\le 256$ MB.
```
================================================================================
BENCHMARK 1 RESULTS:
  Total Rows:           200,000 (~18 MB synthetic CSV)
  Total Time:           22.91 seconds (22,913 ms)
  Throughput:           8,729 rows/second
  Baseline Heap:        31 MB
  Peak Heap:            31 MB (Cap: 256 MB)
  Evaluation:           PASS (Time: 22.91s <= 60s | Peak Heap: 31 MB <= 256 MB)
================================================================================
```

### Benchmark 2: Event Ingestion Throughput & Latency (`EventIngestionBenchmark`)
Measured over 1,000 real-time events across worker concurrency levels:
| Concurrency | Throughput | Latency p50 | Latency p90 | Latency p95 | Latency p99 |
|---|---|---|---|---|---|
| **1 thread** | 264 ops/sec | 1.86 ms | 6.60 ms | 13.57 ms | 30.08 ms |
| **4 threads** | 708 ops/sec | 4.16 ms | 9.38 ms | 14.47 ms | 21.58 ms |
| **8 threads** | 1,959 ops/sec | 3.41 ms | 5.92 ms | 7.16 ms | 11.74 ms |
| **16 threads** | 2,195 ops/sec | 5.23 ms | 12.64 ms | 16.15 ms | 33.16 ms |

### Benchmark 3: Concurrency Stress Test (`ConcurrencyStressTest`)
- **Workload:** 4 threads executing 20 sequential version updates across 50 employees (1,000 updates) + 2 outbox relay workers continuously claiming and publishing + 1 concurrent CSV import (2,000 rows).
- **Result:** Completed in **10.19 seconds**. 0 deadlocks, strict version monotonicity preserved, exactly 0 duplicate publications.

### Benchmark 4: Malformed CSV & Resilience Test (`LargeFileResilienceTest`)
- **20,000 rows with 10% malformed lines:** Processed in 15.05 seconds with 0 MB heap delta ($O(1)$ streaming). All syntax errors captured in `import_row_error` with zero raw PII logged.
- **Duplicate ID detection:** 1,000 rows with duplicate employee IDs immediately held in `NEEDS_REVIEW`; 0 destructive updates applied.

---

## 4. Failure Modes & Mitigation Matrix

| Failure Mode | Trigger / Scenario | RosterGuard Mitigation | Automated Test |
|---|---|---|---|
| **F1: Out-of-Order Events** | Older event (v2) arrives after newer (v3) | Atomic conditional SQL ignores stale update (`409 STALE`). | `EmployeeConcurrencyIT` |
| **F2: Duplicate Deliveries** | HR system resends same payload | SHA-256 payload hash match returns `200 DUPLICATE` (no-op). | `EmployeeSyncServiceIT` |
| **F3: Same-Version Conflict** | Identical version with different payload | Routed to `employee_event_review` for operator decision. | `EmployeeSyncServiceIT` |
| **F4: Truncated CSV File** | 50% of employees missing from CSV | Missing ratio $\ge 30\%$ triggers `AWAITING_APPROVAL` tripwire. | `RosterImportIT` |
| **F5: Malformed CSV Rows** | Syntax error in profile row | Field error logged without PII; employee ID recorded in seen list. | `LargeFileResilienceTest` |
| **F6: Duplicate IDs in Snapshot**| Same ID appears twice in CSV | Detector halts reconciliation; status set to `NEEDS_REVIEW`. | `LargeFileResilienceTest` |
| **F7: Stale CSV Snapshot** | Old snapshot uploaded after events | Source watermark condition `source_updated_at <= snapshot_at` protects newer data. | `RosterImportIT` |
| **F8: Partial Deactivation Crash**| DB connection drops during seat release | Wrapped in single `@Transactional`; all side-effects roll back atomically. | `DeactivationTransactionRollbackIT` |
| **F9: Worker Crash on Publish** | Relay crashes after sending message | Atomic CTE lease expires after 60s; retried by next worker (**at least once**). | `OutboxRelayIT` |
| **F10: Repeated Downstream Outage**| Downstream publisher down | Exponential backoff (`base * 2^attempts`); moves to `FAILED` DLQ after 5 attempts. | `OutboxRelayIT` |

---

## 5. Quickstart & Reproducible Demo

### Prerequisites
- Docker & Docker Compose
- `curl` and `jq` (optional, for JSON formatting)

### 1. Launch Environment
```bash
docker compose up --build
```
*Starts PostgreSQL on `:5432` and RosterGuard on `:8080` with the `dev` profile active (auto-seeding `tenant-1` and `tenant-2` with offices `BLR`, `PUN`, `HYD`).*

### 2. Mint Demo Tokens
```bash
# Mint an HR Integration Token
HR_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"hr-bot","tenant":"tenant-1","roles":["HR_INTEGRATION"]}' | jq -r .token)

# Mint a Transport Admin Token
TA_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"transport-lead","tenant":"tenant-1","roles":["TRANSPORT_ADMIN"]}' | jq -r .token)

# Mint an Operator Token
OP_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"ops-admin","tenant":"tenant-1","roles":["OPERATOR"]}' | jq -r .token)
```

### 3. Ingest Real-Time HR Events
```bash
# Create employee E-101 (version 1)
curl -i -X POST http://localhost:8080/api/v1/employees/events \
  -H "Authorization: Bearer $HR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "CREATE",
    "employeeId": "E-101",
    "sourceVersion": 1,
    "sourceUpdatedAt": "2024-01-15T09:00:00Z",
    "profile": {
      "name": "Alice Johnson",
      "email": "alice@company.com",
      "phone": "+919876543210",
      "officeCode": "BLR",
      "shiftCode": "GENERAL"
    }
  }'

# Update employee E-101 (version 2)
curl -i -X POST http://localhost:8080/api/v1/employees/events \
  -H "Authorization: Bearer $HR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "UPDATE",
    "employeeId": "E-101",
    "sourceVersion": 2,
    "sourceUpdatedAt": "2024-01-15T10:00:00Z",
    "profile": {
      "name": "Alice Johnson",
      "email": "alice.j@company.com",
      "phone": "+919876543210",
      "officeCode": "BLR",
      "shiftCode": "NIGHT"
    }
  }'
```

### 4. Update Transport-Managed Fields
```bash
# Transport admin sets pickup point and assistance (does not affect HR version)
curl -i -X PUT http://localhost:8080/api/v1/employees/E-101/transport \
  -H "Authorization: Bearer $TA_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupPoint": "Koramangala 4th Block Water Tank",
    "specialAssistance": "Wheelchair ramp required"
  }'
```

### 5. Execute Nightly CSV Import
```bash
# Upload a roster CSV snapshot
curl -i -X POST http://localhost:8080/api/v1/roster/imports \
  -H "Authorization: Bearer $OP_TOKEN" \
  -F "file=@sample_roster.csv" \
  -F "snapshotGeneratedAt=2024-01-15T12:00:00Z"
```

### 6. Monitor Transactional Outbox
```bash
# View pending outbox events (operator only)
curl -s -X GET http://localhost:8080/api/v1/ops/outbox/pending \
  -H "Authorization: Bearer $OP_TOKEN" | jq .
```

---

## 6. Running Tests

```bash
# Run all 165 correctness and integration tests (requires Docker for Testcontainers)
mvn -B -ntp clean test

# Run individual benchmarks
mvn test -Dtest=CsvReconciliationBenchmark
mvn test -Dtest=EventIngestionBenchmark
```

---

## 7. Limitations & Production Scale Considerations

1. **At-Least-Once Outbox Delivery:** Workers guarantee at-least-once delivery. If a worker process is terminated after delivering an event to an external broker but before committing the status in PostgreSQL, redelivery will occur. Downstream consumers must deduplicate events by `id` or `dedup_key`.
2. **Transport Edits Concurrency:** Transport field updates use last-write-wins with an internal `transport_version`. In high-concurrency transport admin environments, optimistic locking (`ETag` / `If-Match`) should be introduced.
3. **Multi-Node Outbox Scaling:** The single-node database polling worker with `SKIP LOCKED` scales comfortably to thousands of events/sec. For tens of thousands of events/sec, Debezium CDC (Change Data Capture) reading PostgreSQL WAL logs directly into Apache Kafka should replace polling.
