-- V2: reconcile schema with the final requirements (see docs/DESIGN_DECISIONS.md D1b, D3, D9, D10).
-- Nothing has been deployed with V1, so the NOT NULL column below is safe on an empty table.

-- Server time is audit-only; the HR watermark is a separate, nullable, HR-supplied column.
ALTER TABLE employee RENAME COLUMN hr_updated_at TO applied_at;
ALTER TABLE employee ADD COLUMN source_updated_at TIMESTAMPTZ;

-- SHA-256 hex = 64 chars. roster_hash: reconciliation. payload_hash: same-version conflict detection.
ALTER TABLE employee RENAME COLUMN row_hash TO roster_hash;
ALTER TABLE employee ALTER COLUMN roster_hash TYPE CHAR(64);
ALTER TABLE employee ADD COLUMN payload_hash CHAR(64) NOT NULL;

-- Same-version, different-payload events: kept for human review, never applied.
CREATE TABLE employee_event_review (
    tenant_id            VARCHAR(64) NOT NULL,
    id                   BIGINT GENERATED ALWAYS AS IDENTITY,
    employee_id          VARCHAR(64) NOT NULL,
    event_type           VARCHAR(16) NOT NULL,
    source_version       BIGINT NOT NULL,
    stored_payload_hash  CHAR(64) NOT NULL,
    incoming_payload_hash CHAR(64) NOT NULL,
    payload              JSONB NOT NULL,
    received_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    status               VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee (tenant_id, employee_id),
    CONSTRAINT review_status_chk CHECK (status IN ('PENDING', 'RESOLVED')),
    -- the same conflicting payload re-sent does not create another review row
    UNIQUE (tenant_id, employee_id, source_version, incoming_payload_hash)
);

CREATE TABLE roster_import (
    tenant_id              VARCHAR(64) NOT NULL REFERENCES tenant (id),
    id                     UUID NOT NULL DEFAULT gen_random_uuid(),
    status                 VARCHAR(24) NOT NULL DEFAULT 'STAGING',
    snapshot_generated_at  TIMESTAMPTZ,
    active_count_at_start  INT,
    total_rows             INT NOT NULL DEFAULT 0,
    rejected_rows          INT NOT NULL DEFAULT 0,
    new_count              INT NOT NULL DEFAULT 0,
    changed_count          INT NOT NULL DEFAULT 0,
    missing_count          INT NOT NULL DEFAULT 0,
    hold_reason            VARCHAR(64),
    approved_by            VARCHAR(100),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CONSTRAINT import_status_chk CHECK (status IN
        ('STAGING', 'COMPLETED', 'AWAITING_APPROVAL', 'NEEDS_REVIEW', 'REJECTED', 'FAILED'))
);

-- Valid, non-duplicate rows only. Duplicates and invalid rows are never staged for apply.
CREATE TABLE roster_staging (
    tenant_id         VARCHAR(64) NOT NULL,
    import_id         UUID NOT NULL,
    employee_id       VARCHAR(64) NOT NULL,
    name              VARCHAR(200),
    email             VARCHAR(254),
    phone             VARCHAR(20),
    manager_id        VARCHAR(64),
    office_code       VARCHAR(32),
    shift_code        VARCHAR(32),
    source_version    BIGINT NOT NULL,
    source_updated_at TIMESTAMPTZ,
    roster_hash       CHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, import_id, employee_id),
    FOREIGN KEY (tenant_id, import_id) REFERENCES roster_import (tenant_id, id) ON DELETE CASCADE
);

-- Every usable employee ID encountered, valid row or not. Anything in here is never "missing".
CREATE TABLE import_seen_id (
    tenant_id    VARCHAR(64) NOT NULL,
    import_id    UUID NOT NULL,
    employee_id  VARCHAR(64) NOT NULL,
    occurrences  INT NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, import_id, employee_id),
    FOREIGN KEY (tenant_id, import_id) REFERENCES roster_import (tenant_id, id) ON DELETE CASCADE
);

-- Error report: field and code only, never the submitted value (no personal data).
CREATE TABLE import_row_error (
    tenant_id    VARCHAR(64) NOT NULL,
    id           BIGINT GENERATED ALWAYS AS IDENTITY,
    import_id    UUID NOT NULL,
    row_number   BIGINT NOT NULL,
    employee_id  VARCHAR(64),
    field        VARCHAR(64),
    code         VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, import_id) REFERENCES roster_import (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE booking (
    tenant_id     VARCHAR(64) NOT NULL,
    id            UUID NOT NULL DEFAULT gen_random_uuid(),
    employee_id   VARCHAR(64) NOT NULL,
    start_time    TIMESTAMPTZ NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    cancelled_at  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee (tenant_id, employee_id),
    CONSTRAINT booking_status_chk CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);
CREATE INDEX booking_future_idx ON booking (tenant_id, employee_id, start_time)
    WHERE status = 'CONFIRMED';

CREATE TABLE seat_allocation (
    tenant_id     VARCHAR(64) NOT NULL,
    booking_id    UUID NOT NULL,
    seat_id       VARCHAR(32) NOT NULL,
    released_at   TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, booking_id),
    FOREIGN KEY (tenant_id, booking_id) REFERENCES booking (tenant_id, id)
);

CREATE TABLE outbox_event (
    tenant_id       VARCHAR(64) NOT NULL,
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id    VARCHAR(64) NOT NULL,
    event_type      VARCHAR(32) NOT NULL,
    dedup_key       VARCHAR(200) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, aggregate_id) REFERENCES employee (tenant_id, employee_id),
    CONSTRAINT outbox_status_chk CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    UNIQUE (tenant_id, dedup_key)
);
CREATE INDEX outbox_pending_idx ON outbox_event (next_attempt_at) WHERE status = 'PENDING';
