-- Baseline schema. Every table is tenant-scoped; keys lead with tenant_id so that
-- tenant isolation is enforced by the access path, not just by convention.

CREATE TABLE tenant (
    id   VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL
);

CREATE TABLE office (
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenant (id),
    code      VARCHAR(32) NOT NULL,
    name      VARCHAR(200) NOT NULL,
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE employee (
    tenant_id         VARCHAR(64) NOT NULL REFERENCES tenant (id),
    employee_id       VARCHAR(64) NOT NULL,

    -- HR-owned fields (phone and shift_code ownership: see docs/DESIGN_DECISIONS.md D4, D5)
    name              VARCHAR(200),
    email             VARCHAR(254),
    phone             VARCHAR(20),
    manager_id        VARCHAR(64),
    office_code       VARCHAR(32),
    shift_code        VARCHAR(32),
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    source_version    BIGINT NOT NULL,
    row_hash          CHAR(32) NOT NULL,
    hr_updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Transport-admin-owned fields, with their own version so they never conflict with HR's
    pickup_point      VARCHAR(500),
    special_assistance VARCHAR(500),
    transport_version BIGINT NOT NULL DEFAULT 0,

    PRIMARY KEY (tenant_id, employee_id),
    FOREIGN KEY (tenant_id, office_code) REFERENCES office (tenant_id, code),
    CONSTRAINT employee_status_chk CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT employee_version_chk CHECK (source_version >= 0)
);

-- Serves "count active" and "missing from roster" queries without scanning inactive rows.
CREATE INDEX employee_active_idx ON employee (tenant_id) WHERE status = 'ACTIVE';

