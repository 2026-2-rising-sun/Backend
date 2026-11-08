ALTER TABLE refund_request
    ADD COLUMN retry_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN execution_started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN next_action_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token VARCHAR(36),
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_refund_retry_count CHECK (retry_count BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_refund_lease_pair CHECK (
        (lease_token IS NULL AND lease_until IS NULL) OR
        (lease_token IS NOT NULL AND lease_until IS NOT NULL)
    );

-- An UNKNOWN or terminal legacy request has already entered the execution path.
UPDATE refund_request SET execution_started_at=requested_at
 WHERE status IN ('UNKNOWN', 'SUCCESS', 'FAILED');

CREATE INDEX ix_refund_recovery_due
    ON refund_request (next_action_at, id)
    WHERE status IN ('PROCESSING', 'UNKNOWN');
