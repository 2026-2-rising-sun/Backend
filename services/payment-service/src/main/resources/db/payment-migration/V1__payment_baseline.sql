-- 빈 전용 Payment DB용 최종 스키마. 다른 서비스 테이블을 참조하지 않는다.
CREATE TABLE payment_attempt (
 id BIGINT PRIMARY KEY,
 request_id VARCHAR(36) NOT NULL UNIQUE, payment_id BIGINT NOT NULL UNIQUE,
 order_id BIGINT NOT NULL, payment_group_id BIGINT, reference_number VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL CHECK(amount>=0), request_key VARCHAR(64),
 scenario VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
 requested_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_at TIMESTAMP WITH TIME ZONE,
 scheduled_resolve_at TIMESTAMP WITH TIME ZONE NOT NULL,
 retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND 3),
 execution_started_at TIMESTAMP WITH TIME ZONE, lease_token VARCHAR(36), lease_until TIMESTAMP WITH TIME ZONE,
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK ((lease_token IS NULL)=(lease_until IS NULL)),
 CHECK (status IN ('PENDING','PROCESSING','UNKNOWN','SUCCESS','FAILED','TIMEOUT')),
 CHECK (scenario IN ('INSTANT_SUCCESS','INSTANT_FAIL','DELAYED_SUCCESS','DELAYED_FAIL','SUCCESS_RESPONSE_LOST','FAILURE_RESPONSE_LOST','UNAVAILABLE_BEFORE_RESULT'))
);
CREATE INDEX ix_payment_recovery ON payment_attempt(scheduled_resolve_at,id) WHERE status IN ('PROCESSING','UNKNOWN');
CREATE TABLE mock_gateway_result (attempt_id BIGINT PRIMARY KEY REFERENCES payment_attempt(id),outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('SUCCESS','FAILED','TIMEOUT')),authorized_at TIMESTAMP WITH TIME ZONE NOT NULL);
CREATE TABLE refund_execution (
 id BIGINT PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE, core_refund_request_id BIGINT NOT NULL UNIQUE,
 payment_id BIGINT NOT NULL REFERENCES payment_attempt(payment_id), order_id BIGINT NOT NULL,
 payment_group_id BIGINT NOT NULL, reference_number VARCHAR(64) NOT NULL,
 refund_amount BIGINT NOT NULL CHECK(refund_amount>=0), status VARCHAR(32) NOT NULL CHECK(status IN ('PROCESSING','UNKNOWN','SUCCESS','FAILED')),
 idempotency_key VARCHAR(64) NOT NULL, request_fingerprint VARCHAR(64) NOT NULL,
 requested_at TIMESTAMP WITH TIME ZONE NOT NULL,resolved_at TIMESTAMP WITH TIME ZONE,result_detail VARCHAR(512),
 retry_count SMALLINT NOT NULL DEFAULT 0 CHECK(retry_count BETWEEN 0 AND 3),execution_started_at TIMESTAMP WITH TIME ZONE,
 next_action_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token VARCHAR(36),lease_until TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CHECK((lease_token IS NULL)=(lease_until IS NULL))
);
CREATE INDEX ix_refund_execution_due ON refund_execution(next_action_at,id) WHERE status IN ('PROCESSING','UNKNOWN');
CREATE TABLE mock_refund_result (
 refund_request_id BIGINT PRIMARY KEY REFERENCES refund_execution(id),refund_amount BIGINT NOT NULL CHECK(refund_amount>=0),
 outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('SUCCESS','FAILED')),refund_reference VARCHAR(64) NOT NULL UNIQUE,recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
