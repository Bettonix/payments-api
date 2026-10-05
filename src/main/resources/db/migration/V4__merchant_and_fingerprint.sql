-- V4: Multi-tenant idempotency constraint and merchant indexes

-- Drop old global unique constraint on idempotency_key
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_idempotency_key_uq;

-- Add compound unique constraint (merchant_id, idempotency_key)
ALTER TABLE payments ADD CONSTRAINT payments_merchant_idem_uq UNIQUE (merchant_id, idempotency_key);

-- Index for querying merchant's payments ordered by creation
CREATE INDEX IF NOT EXISTS idx_payments_merchant_created ON payments (merchant_id, created_at DESC);
