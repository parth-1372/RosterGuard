# Interview notes (living document)

## M1 Foundation
- **Why real PostgreSQL in tests (Testcontainers), not H2?** The correctness rules rely on
  PostgreSQL behaviour (atomic conditional UPDATE, partial indexes, SKIP LOCKED later). H2 can
  hide differences. Trade-off: tests need Docker and are slower (~30s startup).
- **Why Flyway?** Versioned, repeatable schema changes; the same migration runs in tests and prod.
- **Why constraints in SQL?** The CHECK on `source_version` shows invariants are enforced by the
  database, not only application code (tested).
- **Why `tenant_id` first in every key?** Tenant-scoped access path; isolation is structural.
- **Why JDBC starter, not JPA yet?** Import/diff and the conditional version update need
  set-based SQL. Decision to add JPA is deferred until it earns its place.
- **Simplest alternative considered:** one container with embedded DB. Rejected: not what production runs.
- **Scale change:** managed Postgres, migrations run as a separate job, not at app start.

## Open items
See docs/DESIGN_DECISIONS.md (D1 version contract, D3 bad-CSV rules, D4/D5 assumptions).
Benchmarks: PENDING.


## M2 Versioned employee sync
- **Idempotency in one sentence:** processing the same event twice has the same final effect as once. Key = (tenant, employeeId, sourceVersion); detected by comparing with the stored version, so no separate dedup table.
- **Why one atomic SQL statement?** INSERT ... ON CONFLICT DO UPDATE ... WHERE stored.version < incoming. Check and write cannot interleave, so an older update can never overwrite a newer one, with no explicit lock. Test: 40 shuffled versions on 16 threads, 5 rounds, final is always v40.
- **Same version, different payload:** ambiguous, so it is stored in employee_event_review and never applied. Payload hash (type + HR fields) tells duplicate from conflict.
- **Field ownership is structural:** HR SQL names only HR columns; transport SQL only transport columns. Transport has its own transport_version.
- **Old-file protection:** compare HR-supplied source_updated_at with HR-supplied snapshotGeneratedAt, never with server time (applied_at is audit only). Missing watermark => hold for review.
- **Why no controllers yet:** the tenant must come from the authenticated principal (M3), not the request.
- **Trade-off:** concurrent transport edits are last-write-wins. Per-event transactions mean a batch is not atomic (by design: one bad record must not fail the batch).
- **Scale change:** batch events in one multi-row statement; partition by tenant.
