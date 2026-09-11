-- V1: core payments table
CREATE TABLE payments (
    id              UUID         PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    payer_id        UUID         NOT NULL,
    payee_id        UUID         NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT payments_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT payments_amount_positive    CHECK (amount > 0),
    CONSTRAINT payments_currency_iso       CHECK (char_length(currency) = 3),
    CONSTRAINT payments_status_valid       CHECK (status IN
        ('PENDING','AUTHORIZED','CAPTURED','SETTLED','FAILED','CANCELLED'))
);

CREATE INDEX idx_payments_status      ON payments(status);
CREATE INDEX idx_payments_payer_id    ON payments(payer_id);
CREATE INDEX idx_payments_payee_id    ON payments(payee_id);
CREATE INDEX idx_payments_created_at  ON payments(created_at DESC);

COMMENT ON TABLE  payments IS 'domain aggregate root — payment intents';
COMMENT ON COLUMN payments.idempotency_key IS 'unique key from client header — guarantees idempotency';
COMMENT ON COLUMN payments.version IS 'optimistic lock counter (JPA @Version)';