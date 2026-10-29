# RosterGuard design decisions and assumptions

Independent implementation of a hiring case study. Not an official MoveInSync product.
This file is the authoritative record of assumptions. Entries marked **ASSUMPTION** are
my interpretation of an ambiguous requirement, not something the case study states.

## D1. One version contract for API events and CSV rows
- **Contract (ASSUMPTION):** the HR system supplies a comparable, per-employee,
  monotonically increasing integer `sourceVersion` on every API event AND every CSV row.
- Both paths use the same `VersionPolicy`: higher = apply, equal = duplicate, lower = stale.
- **Never** synthesise a version (e.g. `stored + 1`). An old snapshot must not beat a newer event.
- CSV row semantics: `v > stored` -> changed; `v == stored` and same hash -> unchanged;
  `v == stored` and different hash -> **conflict, held for review**; `v < stored` -> stale, ignored.
- **Missing-employee protection (ASSUMPTION):** the import declares `snapshotGeneratedAt`.
  An employee is only a deactivation candidate if their last HR update is not newer than
  that instant, so an old file cannot deactivate someone created after it was produced.
- **Limitation:** if the HR source cannot supply comparable versions, ordering between the
  two ingestion paths is not guaranteed. This is documented, not hidden.

## D2. Roles
| Role | May |
|---|---|
| `HR_INTEGRATION` | `POST /employees/events` (HR-owned fields) only |
| `TRANSPORT_ADMIN` | update transport fields, read tenant employees. **Cannot** write HR fields |
| `OPERATOR` | imports, approve/reject, ops endpoints. Cannot write employee fields directly |
| `EMPLOYEE` | read own record |
- Demo token issuance exists only under Spring profile `dev` and is absent otherwise (tested).
- Every role is tested for denial on every operation it is not allowed to perform.

## D3. Bad CSV data never causes deactivation
Rules, applied in order:
1. An employee ID that appears in the file in **any** form (valid, rejected or duplicated)
   is **never** a "missing" candidate.
2. **Duplicate employee IDs** in one file: all copies are excluded from apply, the ID is
   reported, and the import is held (`NEEDS_REVIEW`).
3. A row with no usable employee ID cannot be protected by rule 1: the import is held.
4. A row with a usable ID but an invalid field is rejected and reported; that ID is protected.
5. The 30% missing threshold (configurable) holds the import as `AWAITING_APPROVAL`.
6. A held import changes nothing until an operator approves; approval re-checks state.
Approval never re-enables rule 1 for ambiguous IDs.

## D4. `shiftCode` (ASSUMPTION)
The PDF example mentions a shift change but the ownership table omits it. I treat
`shiftCode` as **HR-owned**, carried in the HR snapshot. This is an interpretation of the
example, to be flagged in the design note.

## D5. Phone (ASSUMPTION)
Phone is **HR-owned** and validated at the boundary (the PDF's malformed-phone example).

## D6. Performance evidence
All benchmark results are **PENDING**. Nothing has been measured yet. Numbers will be added
only after a run, with command, machine and JDK details.

## D7. Environment (verified 2026-10-29)
JDK 21.0.13 (Microsoft build), Maven 3.9.9, Docker 28.1.1, Windows 11 amd64.
PostgreSQL runs via Docker Compose. Final one-command run: `docker compose up`.

## D8. Repository
The supplied case-study PDF is git-ignored and not published.

