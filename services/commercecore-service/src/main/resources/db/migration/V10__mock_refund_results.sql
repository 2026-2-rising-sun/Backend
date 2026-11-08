CREATE TABLE mock_refund_result (
    refund_request_id BIGINT PRIMARY KEY REFERENCES refund_request(id),
    refund_amount BIGINT NOT NULL CHECK (refund_amount >= 0),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED')),
    refund_reference VARCHAR(64) NOT NULL UNIQUE,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
