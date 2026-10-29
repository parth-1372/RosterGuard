# RosterGuard design decisions and assumptions

Independent implementation of a hiring case study. Not an official MoveInSync product.
This file is the authoritative record of assumptions. Entries marked **ASSUMPTION** are
my interpretation of an ambiguous requirement, not something the case study states.

## D1. One version contract for API events and CSV rows
- **Contract (ASSUMPTION):** the HR system supplies a comparable, per-employee,
  monotonically increasing integer `sourceVersion` on every API event AND every CSV row.
- Both paths use the same `VersionPolicy`: higher = apply, equal + same payload = duplicate,
  equal + different payload = conflict (held for review), lower = stale.
- **Never** synthesise a version (e.g. `stored + 1`). An old snapshot must not beat a newer event.
- **Limitation:** if the HR source cannot supply comparable versions, ordering between the
  two ingestion paths is not guaranteed. Documented, not hidden.

## D1b. Old-file protection uses a source watermark, not server time
Revised: a server-generated timestamp is NOT comparable with the HR system's clock.
- `employee.source_updated_at`: the HR system's own "last changed" instant, supplied on events
  and CSV rows (nullable). This is the only column compared with the import's
  `snapshotGeneratedAt` (also HR-supplied). Assumption: both come from the same HR clock.
- `employee.applied_at`: server time when we applied the change. **Audit only, never compared
  with HR time.**
- A missing employee is a deactivation candidate only if `source_updated_at IS NOT NULL` and
  `source_updated_at <= snapshotGeneratedAt`.
- If the employee has no `source_updated_at`, or the import has no `snapshotGeneratedAt`,
  the deactivation is **ambiguous**: the import is held for review (NEEDS_REVIEW).

## D2. Roles
| Role | May |
|---|---|
| `HR_INTEGRATION` | `POST /employees/events` (HR-owned fields). **Only** this role. |
| `TRANSPORT_ADMIN` | update transport fields, read tenant employees. **Cannot** write HR fields |
| `OPERATOR` | imports, approve/reject, ops endpoints. Cannot write employee fields directly |
| `EMPLOYEE` | read own record |
- Demo token issuance exists only under Spring profile `dev` and is absent otherwise (tested in M3).
- Every role is tested for denial on every operation it is not allowed to perform (M3).

## D3. Bad CSV data never causes deactivation
1. **Every usable employee ID seen in the file is recorded in `import_seen_id`
   independently of row validation.** An ID in an invalid row is therefore never "missing".
2. **Duplicate employee IDs** in one file: none of the copies is applied, and the import is
   held (`NEEDS_REVIEW`). No destructive reconciliation happens.
3. A row with **no usable employee ID** cannot be protected by rule 1: the import is held.
4. A row with a usable ID but an invalid field is rejected and reported; the ID stays protected.
5. The configurable missing-employee threshold (default 30%) holds the import as `AWAITING_APPROVAL`.
6. A held import changes nothing until an operator approves; approval re-checks state, and
   ambiguous IDs (rules 2 and 3) are never deactivated even after approval.
7. Ambiguous source watermarks (D1b) also hold the import.

## D4. `shiftCode` (ASSUMPTION)
The PDF example mentions a shift change but the ownership table omits it. I treat
`shiftCode` as **HR-owned**, carried in the HR snapshot. Flagged in the design note.

## D5. Phone (ASSUMPTION)
Phone is **HR-owned** and validated at the boundary (E.164 after stripping spaces/hyphens).

## D6. Performance evidence
All benchmark results are **PENDING**. Nothing has been measured yet.

## D7. Environment (verified 2026-10-29)
JDK 21.0.13 (Microsoft build), Maven 3.9.9, Docker 28.1.1, Windows 11 amd64.
PostgreSQL via Docker Compose. Final one-command run: `docker compose up`.

## D8. Repository
The supplied case-study PDF is git-ignored and not published.

## D9. Canonical hashing
Algorithm: **SHA-256, lowercase hex, stored as `CHAR(64)`**.
- Input is canonicalised first: fields trimmed, blank -> null, email lower-cased, phone stripped
  of spaces and hyphens. Each field is hashed as a 4-byte length prefix + UTF-8 bytes
  (null = length -1), after a version tag string. Length prefixes prevent
  `("ab","c")` colliding with `("a","bc")`.
- **Roster hash** (`roster_hash`): tag `ROSTER_V1` + name, email, phone, manager, office, shift.
  Excludes version, status and transport fields. Used by nightly reconciliation to detect "changed".
- **Event payload hash** (`payload_hash`): tag `EVENT_V1` + event type + the same HR fields.
  Used only to tell an identical duplicate from a conflicting same-version event.
  Stored on the employee row (hash of the last applied event).
- **Same version, different payload:** the event is persisted in `employee_event_review`
  (status PENDING) and does NOT change employee state. Identical duplicates stay no-ops.
- Not a security feature: SHA-256 is used for determinism and negligible collision risk.

## D10. Tenant scoping (verified by a schema test in M2)
Every table except `tenant` has `tenant_id` as the first primary-key column. Every foreign key
to employee, roster_import or booking is composite and includes `tenant_id`, so the database
itself rejects a row that points at another tenant's record. All repository queries filter on
`tenant_id`. Tables: employee, office, employee_event_review, roster_import, roster_staging,
import_seen_id, import_row_error, outbox_event, booking, seat_allocation.

## D11. Milestone 2 scope and event semantics
- M2 builds domain rules, validation, the versioned update and transport updates as **services
  plus tests**. HTTP controllers arrive in M3 together with authentication, because the tenant
  must come only from the authenticated principal, never from the request.
- CREATE and UPDATE are both versioned upserts (a late CREATE after an UPDATE is just stale).
  DEACTIVATE on an unknown employee is **rejected** (UNKNOWN_EMPLOYEE).
- A higher-version CREATE/UPDATE on an INACTIVE employee reactivates them.
- Atomicity: the version check and write are one SQL statement
  (`INSERT ... ON CONFLICT DO UPDATE ... WHERE existing.source_version < incoming`), so a
  concurrent older update cannot overwrite a newer one without any explicit lock.
- Transport fields use their own `transport_version`, incremented atomically by the server.
  Concurrent transport edits are last-write-wins (documented trade-off).
- Offices have no management API yet; they are seeded (tests/dev data).
