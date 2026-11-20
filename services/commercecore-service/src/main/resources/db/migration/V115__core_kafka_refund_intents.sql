-- 구 실행 원문을 보존하고 신규 Kafka 환불·시스템 보상의 영속 fence를 분리한다.
CREATE TABLE core_refund_intent (
 refund_request_id VARCHAR(64) PRIMARY KEY,
 payment_group_id BIGINT NOT NULL REFERENCES payment_group(id) ON DELETE CASCADE,
 user_request_id BIGINT UNIQUE REFERENCES refund_request(id) ON DELETE CASCADE,
 purpose VARCHAR(32) NOT NULL CHECK(purpose IN ('USER_REFUND','SYSTEM_COMPENSATION')),
 request_payload TEXT NOT NULL,
 status VARCHAR(16) NOT NULL CHECK(status IN ('REQUESTED','UNKNOWN','COMPLETED','FAILED')),
 outcome VARCHAR(16),
 execution_id VARCHAR(64),
 result_version BIGINT NOT NULL DEFAULT 0,
 result_payload TEXT,
 completed_at TIMESTAMP WITH TIME ZONE,
 failure_code TEXT,
 UNIQUE(payment_group_id,refund_request_id)
);
CREATE UNIQUE INDEX uk_core_system_compensation ON core_refund_intent(payment_group_id)
 WHERE purpose='SYSTEM_COMPENSATION';
ALTER TABLE refund_target_order ADD COLUMN previous_status VARCHAR(32);
CREATE TABLE core_refund_result_event (
 event_id VARCHAR(64) PRIMARY KEY,
 refund_request_id VARCHAR(64) NOT NULL REFERENCES core_refund_intent(refund_request_id) ON DELETE CASCADE,
 payload TEXT NOT NULL
);
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK(status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED','CANCEL_PENDING'));
ALTER TABLE payment_group DROP CONSTRAINT payment_group_status_check;
ALTER TABLE payment_group ADD CONSTRAINT payment_group_status_check CHECK(status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED','CANCEL_PENDING'));
-- 보상 접수와 같은 그룹 잠금 아래 송신 시작을 영속화한다. 외부 HTTP 동안 잠금은 유지하지 않는다.
ALTER TABLE order_payment_relay ADD COLUMN approval_send_started_at TIMESTAMP WITH TIME ZONE;
