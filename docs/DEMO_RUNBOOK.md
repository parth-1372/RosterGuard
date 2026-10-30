# RosterGuard — Evaluator Demo Runbook

Step-by-step guide to run the end-to-end demo of RosterGuard locally or via Docker Compose.

---

## 1. Environment Startup

### Option A: Via Docker Compose (Recommended)
```bash
docker compose up --build
```
*RosterGuard starts on `http://localhost:8080` with the `dev` profile active and PostgreSQL on port `5432`.*

### Option B: Local JVM + Docker PostgreSQL
```bash
# Start PostgreSQL container
docker run -d --name rosterguard-pg -p 5432:5432 \
  -e POSTGRES_DB=rosterguard \
  -e POSTGRES_USER=rosterguard \
  -e POSTGRES_PASSWORD=rosterguard_dev \
  postgres:16-alpine

# Run the Spring Boot application under dev profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## 2. Step 1: Mint Demo JWT Tokens

In the `dev` profile, demo tokens can be minted directly via `/api/dev/token`:

```bash
# 1. Mint HR Integration Token
HR_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"hr-bot","tenant":"tenant-1","roles":["HR_INTEGRATION"]}' | jq -r .token)

# 2. Mint Transport Admin Token
TA_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"transport-lead","tenant":"tenant-1","roles":["TRANSPORT_ADMIN"]}' | jq -r .token)

# 3. Mint Operator Token
OP_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"ops-admin","tenant":"tenant-1","roles":["OPERATOR"]}' | jq -r .token)

echo "Tokens successfully minted."
```

---

## 3. Step 2: Real-Time HR Event Ingestion & Outbox Events

Ingest two employees at version 1:
```bash
curl -s -X POST http://localhost:8080/api/v1/employees/events \
  -H "Authorization: Bearer $HR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
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
        "type": "CREATE",
        "employeeId": "E-102",
        "sourceVersion": 1,
        "sourceUpdatedAt": "2024-01-15T09:00:00Z",
        "name": "Bob Smith",
        "email": "bob@company.com",
        "phone": "+919876543211",
        "managerId": "M-01",
        "officeCode": "PUN",
        "shiftCode": "NIGHT"
      }
    ]
  }' | jq .
```
*Expected: Both events return `outcome: "APPLIED"`.*

### Verify Outbox Events Produced:
```bash
curl -s -X GET "http://localhost:8080/api/v1/ops/outbox?limit=5" \
  -H "Authorization: Bearer $OP_TOKEN" | jq .
```
*Expected: `EMPLOYEE_CREATED` events exist for `E-101` and `E-102`.*

---

## 4. Step 3: Out-of-Order & Duplicate Protection

### Test Stale Event (Version 0 < Stored 1):
```bash
curl -s -X POST http://localhost:8080/api/v1/employees/events \
  -H "Authorization: Bearer $HR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "events": [{
      "type": "UPDATE",
      "employeeId": "E-101",
      "sourceVersion": 0,
      "sourceUpdatedAt": "2024-01-15T08:00:00Z",
      "name": "Alice Stale",
      "email": "alice@company.com",
      "phone": "+919876543210",
      "officeCode": "BLR"
    }]
  }' | jq .
```
*Expected: `outcome: "STALE"`.*

### Test Split-Brain Same-Version Conflict:
```bash
curl -s -X POST http://localhost:8080/api/v1/employees/events \
  -H "Authorization: Bearer $HR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "events": [{
      "type": "UPDATE",
      "employeeId": "E-101",
      "sourceVersion": 1,
      "sourceUpdatedAt": "2024-01-15T09:00:00Z",
      "name": "Alice Contradictory Name",
      "email": "alice@company.com",
      "phone": "+919876543210",
      "officeCode": "BLR"
    }]
  }' | jq .
```
*Expected: `outcome: "CONFLICT_HELD"` (diverted to `employee_event_review`, stored row unaltered).*

---

## 5. Step 4: Transport-Owned Field Edits

Update pickup point and wheelchair assistance:
```bash
curl -i -X PUT http://localhost:8080/api/v1/employees/E-101/transport \
  -H "Authorization: Bearer $TA_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupPoint": "Koramangala 4th Block Water Tank",
    "specialAssistance": "Wheelchair ramp required"
  }'
```
*Expected: `HTTP 204 No Content`.*

### Verify Profile:
```bash
curl -s -X GET http://localhost:8080/api/v1/employees/E-101 \
  -H "Authorization: Bearer $OP_TOKEN" | jq .
```
*Expected: `pickupPoint` and `specialAssistance` populated, `sourceVersion` remains `1`.*

### Verify Transport Outbox Event:
```bash
curl -s -X GET "http://localhost:8080/api/v1/ops/outbox?limit=5" \
  -H "Authorization: Bearer $OP_TOKEN" | jq '.[0]'
```
*Expected: `eventType: "EMPLOYEE_TRANSPORT_UPDATED"`, `dedupKey: "TRANSPORT_UPDATE:E-101:1"`.*

---

## 6. Step 5: Nightly CSV Reconciliation & Safety Gates

### Happy-Path CSV Import:
```bash
curl -s -X POST http://localhost:8080/api/v1/roster-imports \
  -H "Authorization: Bearer $HR_TOKEN" \
  -F "file=@samples/happy_path_roster.csv" \
  -F "snapshotGeneratedAt=2024-01-15T12:00:00Z" | jq .
```
*Expected: Status `COMPLETED`, `totalRows: 5`, `newCount: 3`, `changedCount: 2`, `missingCount: 0`.*

### Duplicate-ID Safety Gate Test:
Upload CSV containing duplicate employee ID (`E-101` repeated):
```bash
DUP_RES=$(curl -s -X POST http://localhost:8080/api/v1/roster-imports \
  -H "Authorization: Bearer $HR_TOKEN" \
  -F "file=@samples/duplicate_id_roster.csv" \
  -F "snapshotGeneratedAt=2024-01-15T13:00:00Z")

echo "$DUP_RES" | jq .
IMPORT_ID=$(echo "$DUP_RES" | jq -r .id)
```
*Expected: Status `NEEDS_REVIEW`, `holdReason: "DUPLICATE_EMPLOYEE_ID"`. Zero destructive writes applied.*

---

## 7. Step 6: PII-Safe Error Reporting

Inspect error logs for any failed or held import:
```bash
curl -s -X GET "http://localhost:8080/api/v1/roster-imports/$IMPORT_ID/errors?limit=10" \
  -H "Authorization: Bearer $OP_TOKEN" | jq .
```
*Expected: JSON array containing field names and error codes without raw personal data.*

---

## 8. Step 7: Multi-Tenant Isolation Check

Attempt to read Tenant 1's import from Tenant 2 Operator:
```bash
OP_T2_TOKEN=$(curl -s -X POST http://localhost:8080/api/dev/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"t2-operator","tenant":"tenant-2","roles":["OPERATOR"]}' | jq -r .token)

curl -i -X GET "http://localhost:8080/api/v1/roster-imports/$IMPORT_ID" \
  -H "Authorization: Bearer $OP_T2_TOKEN"
```
*Expected: `HTTP 404 Not Found`. Tenant 2 cannot view or access Tenant 1 data.*

