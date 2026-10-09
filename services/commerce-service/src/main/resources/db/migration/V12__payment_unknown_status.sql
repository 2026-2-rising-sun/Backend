-- Preserve legacy PENDING/TIMEOUT values; only expand the allowed status set.
ALTER TABLE payment_attempt DROP CONSTRAINT ck_payment_attempt_status;
ALTER TABLE payment_attempt ADD CONSTRAINT ck_payment_attempt_status CHECK (
    status IN ('PENDING', 'PROCESSING', 'UNKNOWN', 'SUCCESS', 'FAILED', 'TIMEOUT')
);
