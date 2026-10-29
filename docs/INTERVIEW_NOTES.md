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
