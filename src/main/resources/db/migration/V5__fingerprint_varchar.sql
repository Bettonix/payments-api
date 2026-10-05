-- V5: Alter request_fingerprint to VARCHAR(64) for Hibernate compatibility without bpchar padding
ALTER TABLE payments ALTER COLUMN request_fingerprint TYPE VARCHAR(64);
