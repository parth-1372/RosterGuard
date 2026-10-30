-- V3: Core correctness fixes (stale snapshot protection, keyset pagination index, unified payload hash)

-- 1. Tenant-level latest accepted snapshot watermark (protects against older snapshot imports)
ALTER TABLE tenant ADD COLUMN latest_snapshot_watermark TIMESTAMPTZ;

-- 2. Index for stable outbox keyset pagination (tenant_id, created_at DESC, id DESC)
CREATE INDEX outbox_tenant_created_idx ON outbox_event (tenant_id, created_at DESC, id DESC);

-- 3. Unified payload hash column for staged CSV rows to ensure identical hash between API and CSV
ALTER TABLE roster_staging ADD COLUMN payload_hash CHAR(64);

