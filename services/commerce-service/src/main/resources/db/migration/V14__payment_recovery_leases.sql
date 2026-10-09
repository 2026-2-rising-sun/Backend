ALTER TABLE payment_attempt
 ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0,
 ADD COLUMN execution_started_at TIMESTAMP WITH TIME ZONE,
 ADD COLUMN lease_token VARCHAR(36),
 ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
-- Existing unconfirmed attempts may already have been sent by an earlier binary.
UPDATE payment_attempt SET execution_started_at=requested_at
 WHERE status IN ('PROCESSING','UNKNOWN','SUCCESS','FAILED','TIMEOUT');
ALTER TABLE payment_attempt
 ADD CONSTRAINT ck_payment_retry_count CHECK (retry_count BETWEEN 0 AND 3),
 ADD CONSTRAINT ck_payment_lease_pair CHECK ((lease_token IS NULL) = (lease_until IS NULL));
CREATE INDEX ix_payment_recovery_due ON payment_attempt (scheduled_resolve_at,id)
 WHERE status IN ('PROCESSING','UNKNOWN');
