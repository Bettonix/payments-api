-- V2: outbox pattern for transactional event publishing
--
-- Outbox is the heart of the transactional outbox pattern: domain writes
-- the event in the same DB transaction as the aggregate mutation. A
-- separate relay process reads pending events and publishes them to the
-- broker. This guarantees at-least-once delivery without 2PC.
--
-- Status lifecycle: PENDING -> PUBLISHED (terminal) | FAILED (terminal).
CREATE TABLE outbox_events (
    id            UUID         PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   TIMESTAMP,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count  INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(1000),
    version        BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT outbox_status_valid    CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    CONSTRAINT outbox_agg_type_valid  CHECK (aggregate_type IN ('Payment'))
);

-- primary index for the relay scan: pick pending events oldest-first.
CREATE INDEX idx_outbox_pending ON outbox_events(created_at)
    WHERE status = 'PENDING';

-- secondary index for looking up events by aggregate.
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);

COMMENT ON TABLE  outbox_events     IS 'transactional outbox — events published by OutboxRelay';
COMMENT ON COLUMN outbox_events.status IS 'PENDING until relay publishes; PUBLISHED on success; FAILED after retries exhausted';