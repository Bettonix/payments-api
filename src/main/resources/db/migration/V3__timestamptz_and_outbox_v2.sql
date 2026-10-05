-- V3: TIMESTAMPTZ, outbox lease & retry scheduling, and multi-tenant preparation

ALTER TABLE payments
  ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
  ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
  ADD COLUMN merchant_id VARCHAR(64) NOT NULL DEFAULT 'legacy',
  ADD COLUMN request_fingerprint CHAR(64);

ALTER TABLE outbox_events
  ALTER COLUMN created_at   TYPE TIMESTAMPTZ USING created_at   AT TIME ZONE 'UTC',
  ALTER COLUMN published_at TYPE TIMESTAMPTZ USING published_at AT TIME ZONE 'UTC',
  ADD COLUMN merchant_id    VARCHAR(64) NOT NULL DEFAULT 'legacy',
  ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN locked_until    TIMESTAMPTZ;

DROP INDEX IF EXISTS idx_outbox_pending;
CREATE INDEX idx_outbox_claim     ON outbox_events (next_attempt_at, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_agg_head  ON outbox_events (aggregate_id, created_at)    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_published ON outbox_events (published_at)                 WHERE status = 'PUBLISHED';
CREATE INDEX idx_payments_merchant_idem ON payments (merchant_id, idempotency_key);
