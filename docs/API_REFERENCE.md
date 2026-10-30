# RosterGuard — Complete API Reference

Authoritative API specification for RosterGuard. All endpoints enforce stateless JWT authentication and tenant scoping unless explicitly marked public.

---

## 1. Authentication & Tenant Context

- **Token Standard:** RFC 7519 JWT signed with HMAC-SHA256 (`HS256`).
- **Required Claims:**
  - `sub`: Authenticated user ID (e.g. `"hr-bot"`, `"ops-admin"`).
  - `tenant`: Tenant ID string (e.g. `"tenant-1"`). **Tenant isolation is enforced strictly from this claim.**
  - `roles`: JSON array of roles (`"HR_INTEGRATION"`, `"TRANSPORT_ADMIN"`, `"OPERATOR"`, `"EMPLOYEE"`).
  - `employeeId`: Required only when the role includes `"EMPLOYEE"`.
- **Header Format:** `Authorization: Bearer <token>`
- **Error Responses for Authentication:**
  - `401 Unauthorized`: Missing, expired, invalid signature, or token missing required claims.
  - `403 Forbidden`: Token valid, but user does not hold the required role for the path.

---

## 2. Dev Token Minting (Development Only)

### `POST /api/dev/token`
- **Purpose:** Mints demo JWTs locally for manual testing and evaluation. Available only when running under the Spring profile `dev`.
- **Required Role:** None (Public in `dev` profile; unavailable / 401 in production).
- **Request Headers:** `Content-Type: application/json`
- **Request Body:**
  ```json
  {
    "userId": "ops-admin",
    "tenant": "tenant-1",
    "roles": ["OPERATOR"],
    "employeeId": null,
    "ttlSeconds": 3600
  }
  ```
  | Field | Type | Required | Description |
  |---|---|---|---|
  | `userId` | string | Yes | Subject identifier |
  | `tenant` | string | Yes | Tenant scope |
  | `roles` | array[string] | Yes | At least one valid role (`HR_INTEGRATION`, `TRANSPORT_ADMIN`, `OPERATOR`, `EMPLOYEE`) |
  | `employeeId` | string | Conditional | Required if `roles` contains `EMPLOYEE` |
  | `ttlSeconds` | number | No | Token lifetime (default 900s, max 3600s) |

- **Success Response (`200 OK`):**
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expiresAt": "2026-10-30T09:00:00Z"
  }
  ```
- **Error Responses:**
  - `400 Bad Request`: Validation failure (e.g. `roles: INVALID_ROLE`, `tenant: REQUIRED`).
- **Example cURL:**
  ```bash
  curl -s -X POST http://localhost:8080/api/dev/token \
    -H "Content-Type: application/json" \
    -d '{"userId":"ops-admin","tenant":"tenant-1","roles":["OPERATOR"]}'
  ```

---

## 3. Real-Time HR Ingestion

### `POST /api/v1/employees/events`
- **Purpose:** Ingests a batch of real-time HR events (creates, updates, deactivations) with atomic per-event version checks and transactional outbox event production.
- **Required Role:** `HR_INTEGRATION`
- **Request Headers:**
  - `Authorization: Bearer <HR_TOKEN>`
  - `Content-Type: application/json`
- **Request Body:**
  ```json
  {
    "events": [
      {
        "type": "CREATE",
        "employeeId": "E-101",
        "sourceVersion": 1,
        "sourceUpdatedAt": "2024-01-15T09:00:00Z",
        "name": "Alice Johnson",
        "email": "alice@company.com",
        "phone": "+919876543210",
        "managerId": "M-01",
        "officeCode": "BLR",
        "shiftCode": "GENERAL"
      },
      {
        "type": "UPDATE",
        "employeeId": "E-102",
        "sourceVersion": 4,
        "sourceUpdatedAt": "2024-01-15T09:30:00Z",
        "name": "Bob Smith",
        "email": "bob@company.com",
        "phone": "+919876543211",
        "managerId": "M-01",
        "officeCode": "PUN",
        "shiftCode": "NIGHT"
      },
      {
        "type": "DEACTIVATE",
        "employeeId": "E-103",
        "sourceVersion": 2,
        "sourceUpdatedAt": "2024-01-15T10:00:00Z"
      }
    ]
  }
  ```
  | Field | Type | Required | Description |
  |---|---|---|---|
  | `events` | array | Yes | Batch of 1 to 1,000 events |
  | `events[].type` | string | Yes | `CREATE`, `UPDATE`, or `DEACTIVATE` |
  | `events[].employeeId` | string | Yes | Employee ID (`[A-Za-z0-9_-]{1,64}`) |
  | `events[].sourceVersion`| number | Yes | Monotonically increasing version |
  | `events[].sourceUpdatedAt`| string | No | ISO-8601 UTC timestamp of source change |
  | `events[].name` | string | For CREATE/UPDATE | Full employee name (max 200 chars) |
  | `events[].email` | string | For CREATE/UPDATE | Valid email address |
  | `events[].phone` | string | For CREATE/UPDATE | Normalized phone (E.164 pattern `^\+[1-9][0-9]{7,14}$`) |
  | `events[].officeCode` | string | For CREATE/UPDATE | Office code validated against tenant offices |
  | `events[].shiftCode` | string | No | Shift code identifier |

- **Success Response (`200 OK`):**
  ```json
  {
    "results": [
      {
        "employeeId": "E-101",
        "outcome": "APPLIED",
        "errors": []
      },
      {
        "employeeId": "E-102",
        "outcome": "APPLIED",
        "errors": []
      },
      {
        "employeeId": "E-103",
        "outcome": "APPLIED",
        "errors": []
      }
    ]
  }
  ```
- **Outcome Status Values:**
  - `APPLIED`: Event version strictly higher than stored; database and outbox updated.
  - `DUPLICATE`: Same version and identical SHA-256 canonical payload; idempotent no-op.
  - `STALE`: Incoming version strictly less than stored version; rejected without mutation.
  - `CONFLICT_HELD`: Same version but conflicting payload; held in `employee_event_review` for human operator decision.
  - `REJECTED`: Format or schema validation failed on fields.
  - `ERROR`: Unexpected database error; individual event rolled back.
- **Example cURL:**
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/employees/events \
    -H "Authorization: Bearer $HR_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "events": [{
        "type": "CREATE",
        "employeeId": "E-101",
        "sourceVersion": 1,
        "sourceUpdatedAt": "2024-01-15T09:00:00Z",
        "name": "Alice Johnson",
        "email": "alice@company.com",
        "phone": "+919876543210",
        "officeCode": "BLR",
        "shiftCode": "GENERAL"
      }]
    }'
  ```

---

## 4. Employee Query & Transport Management

### `GET /api/v1/employees/{employeeId}`
- **Purpose:** Retrieves a single employee record within the caller's tenant.
- **Required Role:** `TRANSPORT_ADMIN`, `OPERATOR`, or `EMPLOYEE` (restricted to own ID).
- **Request Headers:** `Authorization: Bearer <TOKEN>`
- **Success Response (`200 OK`):**
  ```json
  {
    "employeeId": "E-101",
    "name": "Alice Johnson",
    "email": "alice@company.com",
    "phone": "+919876543210",
    "managerId": "M-01",
    "officeCode": "BLR",
    "shiftCode": "GENERAL",
    "status": "ACTIVE",
    "sourceVersion": 1,
    "pickupPoint": "Koramangala 4th Block",
    "specialAssistance": "Wheelchair ramp required"
  }
  ```
- **Error Responses:**
  - `404 Not Found`: Employee does not exist in caller's tenant, or `EMPLOYEE` role attempted to read another employee's profile.
- **Example cURL:**
  ```bash
  curl -s -X GET http://localhost:8080/api/v1/employees/E-101 \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

### `GET /api/v1/employees`
- **Purpose:** Keyset-paginated directory of employees in the caller's tenant ordered by `employee_id`.
- **Required Role:** `TRANSPORT_ADMIN` or `OPERATOR`
- **Request Headers:** `Authorization: Bearer <TOKEN>`
- **Query Parameters:**
  - `after` (string, optional): Keyset cursor (returns employees with `employee_id > after`).
  - `limit` (int, optional, default `50`, max `500`): Page size.
- **Success Response (`200 OK`):**
  ```json
  {
    "employees": [
      {
        "employeeId": "E-101",
        "name": "Alice Johnson",
        "email": "alice@company.com",
        "phone": "+919876543210",
        "managerId": "M-01",
        "officeCode": "BLR",
        "shiftCode": "GENERAL",
        "status": "ACTIVE",
        "sourceVersion": 1,
        "pickupPoint": null,
        "specialAssistance": null
      }
    ],
    "nextAfter": "E-101"
  }
  ```
- **Example cURL:**
  ```bash
  curl -s -X GET "http://localhost:8080/api/v1/employees?limit=20" \
    -H "Authorization: Bearer $TA_TOKEN"
  ```

---

### `PUT /api/v1/employees/{employeeId}/transport`
- **Purpose:** Transport-admin edits of transport-owned fields (`pickupPoint`, `specialAssistance`). Increments internal `transport_version` and enqueues an `EMPLOYEE_TRANSPORT_UPDATED` transactional outbox event. Cannot touch HR-owned fields or the HR `source_version`.
- **Required Role:** `TRANSPORT_ADMIN`
- **Request Headers:**
  - `Authorization: Bearer <TA_TOKEN>`
  - `Content-Type: application/json`
- **Request Body:**
  ```json
  {
    "pickupPoint": "Indiranagar Metro Station Gate 2",
    "specialAssistance": "Hearing impairment escort"
  }
  ```
  | Field | Type | Required | Description |
  |---|---|---|---|
  | `pickupPoint` | string | No | Pickup location name / landmark (max 500 chars) |
  | `specialAssistance` | string | No | Physical or medical assistance instructions (max 500 chars) |

- **Success Response (`204 No Content`)**
- **Error Responses:**
  - `400 Bad Request`: Validation failure (e.g. `pickupPoint` length > 500).
  - `404 Not Found`: Employee does not exist in the caller's tenant.
- **Example cURL:**
  ```bash
  curl -i -X PUT http://localhost:8080/api/v1/employees/E-101/transport \
    -H "Authorization: Bearer $TA_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "pickupPoint": "Indiranagar Metro Station Gate 2",
      "specialAssistance": "Hearing impairment escort"
    }'
  ```

---

## 5. Roster CSV Ingestion & Reconciliation

### `POST /api/v1/roster-imports`
- **Purpose:** Streams a nightly full-roster CSV snapshot into staging, applies defensive safety tripwires, and conditionally upserts employees, cancels bookings, releases seats, and enqueues outbox events in a single set-based transaction.
- **Required Role:** `HR_INTEGRATION`
- **Request Headers:**
  - `Authorization: Bearer <HR_TOKEN>`
  - `Content-Type: multipart/form-data`
- **Form Parameters:**
  - `file` (MultipartFile, required): CSV file (max 32MB file, 34MB request).
  - `snapshotGeneratedAt` (string, optional): ISO-8601 HR clock timestamp used for source watermark protection.
- **CSV Header Contract:**
  Required columns (case-sensitive, any ordering supported):
  ```csv
  employeeId,sourceVersion,sourceUpdatedAt,name,email,phone,managerId,officeCode,shiftCode
  ```
- **Success Response (`202 Accepted`):**
  ```json
  {
    "id": "e44d5c41-9430-4e36-963e-cf54350bc56b",
    "status": "COMPLETED",
    "snapshotGeneratedAt": "2024-01-15T12:00:00Z",
    "activeCountAtStart": 100,
    "totalRows": 105,
    "rejectedRows": 0,
    "newCount": 5,
    "changedCount": 10,
    "missingCount": 0,
    "holdReason": null,
    "createdAt": "2024-01-15T12:00:05Z",
    "completedAt": "2024-01-15T12:00:08Z"
  }
  ```
- **Import Status Values:**
  - `COMPLETED`: Clean snapshot; changes and deactivations committed.
  - `AWAITING_APPROVAL`: Held due to missing-employee threshold (> 30%) or missing source watermark. Upserts applied safely; deactivations deferred for operator review.
  - `NEEDS_REVIEW`: Data quality issue (duplicate IDs in file, missing employee IDs, invalid header, or stale snapshot watermark). Zero mutations applied.
  - `REJECTED`: Held import rejected by an operator.
  - `FAILED`: Uncaught streaming error.
- **Example cURL:**
  ```bash
  curl -i -X POST http://localhost:8080/api/v1/roster-imports \
    -H "Authorization: Bearer $HR_TOKEN" \
    -F "file=@demo/fixtures/happy_path_roster.csv" \
    -F "snapshotGeneratedAt=2024-01-15T12:00:00Z"
  ```

---

### `GET /api/v1/roster-imports/{importId}`
- **Purpose:** Inspects status, progress counters, and hold reasons of a roster import.
- **Required Role:** `OPERATOR`
- **Request Headers:** `Authorization: Bearer <OP_TOKEN>`
- **Success Response (`200 OK`):**
  ```json
  {
    "id": "e44d5c41-9430-4e36-963e-cf54350bc56b",
    "status": "AWAITING_APPROVAL",
    "snapshotGeneratedAt": "2024-01-15T12:00:00Z",
    "activeCountAtStart": 100,
    "totalRows": 60,
    "rejectedRows": 0,
    "newCount": 0,
    "changedCount": 5,
    "missingCount": 40,
    "holdReason": "MISSING_THRESHOLD_EXCEEDED",
    "createdAt": "2024-01-15T12:00:05Z",
    "completedAt": "2024-01-15T12:00:08Z"
  }
  ```
- **Error Responses:**
  - `404 Not Found`: Import does not exist in caller's tenant.
- **Example cURL:**
  ```bash
  curl -s -X GET http://localhost:8080/api/v1/roster-imports/e44d5c41-9430-4e36-963e-cf54350bc56b \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

### `GET /api/v1/roster-imports/{importId}/errors`
- **Purpose:** Retrieves a paginated list of row-level syntax and validation errors encountered during CSV parsing. PII-safe (contains only field names and error codes, never raw values).
- **Required Role:** `OPERATOR`
- **Request Headers:** `Authorization: Bearer <OP_TOKEN>`
- **Query Parameters:**
  - `after` (number, optional): Keyset ID cursor (`id > after`).
  - `limit` (int, optional, default `50`, max `200`): Page limit.
- **Success Response (`200 OK`):**
  ```json
  [
    {
      "id": 1,
      "rowNumber": 14,
      "employeeId": "E-999",
      "field": "phone",
      "code": "INVALID_PHONE"
    },
    {
      "id": 2,
      "rowNumber": 42,
      "employeeId": "E-404",
      "field": "officeCode",
      "code": "UNKNOWN_OFFICE"
    }
  ]
  ```
- **Error Responses:**
  - `404 Not Found`: Import does not exist in caller's tenant.
- **Example cURL:**
  ```bash
  curl -s -X GET "http://localhost:8080/api/v1/roster-imports/e44d5c41-9430-4e36-963e-cf54350bc56b/errors?limit=50" \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

### `POST /api/v1/roster-imports/{importId}/approve`
- **Purpose:** Operator approval of an import held in `AWAITING_APPROVAL`. Atomically transitions state to `COMPLETED`, executes deferred bulk deactivations, cancels future bookings, releases seats, enqueues outbox events, and advances the tenant watermark.
- **Required Role:** `OPERATOR`
- **Request Headers:** `Authorization: Bearer <OP_TOKEN>`
- **Success Response (`200 OK`):**
  ```json
  {
    "id": "e44d5c41-9430-4e36-963e-cf54350bc56b",
    "status": "COMPLETED",
    "snapshotGeneratedAt": "2024-01-15T12:00:00Z",
    "activeCountAtStart": 100,
    "totalRows": 60,
    "rejectedRows": 0,
    "newCount": 0,
    "changedCount": 5,
    "missingCount": 40,
    "holdReason": "MISSING_THRESHOLD_EXCEEDED",
    "createdAt": "2024-01-15T12:00:05Z",
    "completedAt": "2024-01-15T12:05:00Z"
  }
  ```
- **Error Responses:**
  - `404 Not Found`: Import ID not found or import is not in `AWAITING_APPROVAL` status (e.g. concurrent operator race).
- **Example cURL:**
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/roster-imports/e44d5c41-9430-4e36-963e-cf54350bc56b/approve \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

### `POST /api/v1/roster-imports/{importId}/reject`
- **Purpose:** Operator rejection of an import held in `AWAITING_APPROVAL`. Atomically marks status `REJECTED`. Deactivations are cancelled.
- **Required Role:** `OPERATOR`
- **Request Headers:** `Authorization: Bearer <OP_TOKEN>`
- **Success Response (`200 OK`):**
  ```json
  {
    "id": "e44d5c41-9430-4e36-963e-cf54350bc56b",
    "status": "REJECTED",
    "snapshotGeneratedAt": "2024-01-15T12:00:00Z",
    "activeCountAtStart": 100,
    "totalRows": 60,
    "rejectedRows": 0,
    "newCount": 0,
    "changedCount": 5,
    "missingCount": 40,
    "holdReason": "MISSING_THRESHOLD_EXCEEDED",
    "createdAt": "2024-01-15T12:00:05Z",
    "completedAt": "2024-01-15T12:05:00Z"
  }
  ```
- **Error Responses:**
  - `404 Not Found`: Import not found or already completed/rejected.
- **Example cURL:**
  ```bash
  curl -s -X POST http://localhost:8080/api/v1/roster-imports/e44d5c41-9430-4e36-963e-cf54350bc56b/reject \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

## 6. Outbox Monitoring & Operations

### `GET /api/v1/ops/outbox`
- **Purpose:** Operator monitoring of the transactional outbox table for the caller's tenant.
- **Required Role:** `OPERATOR`
- **Request Headers:** `Authorization: Bearer <OP_TOKEN>`
- **Query Parameters:**
  - `status` (string, optional): Filter by `PENDING`, `PUBLISHED`, or `FAILED`.
  - `after` (string, optional): UUID cursor for composite keyset pagination (`(created_at, id)`).
  - `limit` (int, optional, default `20`, max `100`): Page size.
- **Success Response (`200 OK`):**
  ```json
  [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "aggregateId": "E-101",
      "eventType": "EMPLOYEE_CREATED",
      "status": "PUBLISHED",
      "attempts": 1,
      "nextAttemptAt": "2024-01-15T12:00:00Z",
      "lastError": null,
      "createdAt": "2024-01-15T11:59:55Z",
      "publishedAt": "2024-01-15T12:00:02Z"
    }
  ]
  ```
- **Example cURL:**
  ```bash
  curl -s -X GET "http://localhost:8080/api/v1/ops/outbox?status=PENDING&limit=20" \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

### `GET /api/v1/ops/outbox/{id}`
- **Purpose:** Retrieves detail of an individual outbox event by UUID within caller's tenant.
- **Required Role:** `OPERATOR`
- **Request Headers:** `Authorization: Bearer <OP_TOKEN>`
- **Success Response (`200 OK`):**
  ```json
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "aggregateId": "E-101",
    "eventType": "EMPLOYEE_CREATED",
    "status": "PUBLISHED",
    "attempts": 1,
    "nextAttemptAt": "2024-01-15T12:00:00Z",
    "lastError": null,
    "createdAt": "2024-01-15T11:59:55Z",
    "publishedAt": "2024-01-15T12:00:02Z"
  }
  ```
- **Error Responses:**
  - `400 Bad Request`: Malformed UUID.
  - `404 Not Found`: Event does not exist in caller's tenant.
- **Example cURL:**
  ```bash
  curl -s -X GET http://localhost:8080/api/v1/ops/outbox/550e8400-e29b-41d4-a716-446655440000 \
    -H "Authorization: Bearer $OP_TOKEN"
  ```

---

## 7. Actuator & Health

### `GET /actuator/health`
- **Purpose:** Liveness and readiness probes for Docker Compose and orchestrators.
- **Required Role:** None (Public)
- **Success Response (`200 OK`):**
  ```json
  {
    "status": "UP",
    "components": {
      "db": {
        "status": "UP",
        "details": {
          "database": "PostgreSQL",
          "validationQuery": "isValid()"
        }
      },
      "diskSpace": {
        "status": "UP"
      },
      "ping": {
        "status": "UP"
      }
    }
  }
  ```
- **Example cURL:**
  ```bash
  curl -s -X GET http://localhost:8080/actuator/health
  ```

