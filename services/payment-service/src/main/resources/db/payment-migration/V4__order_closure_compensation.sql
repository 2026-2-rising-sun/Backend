-- 결제 생성·종료·호출 시작은 같은 제어 행을 먼저 잠근다. 외부 DB 참조는 없다.
CREATE TABLE payment_order_control (
 payment_id BIGINT PRIMARY KEY,
 payment_request_id VARCHAR(36) NOT NULL UNIQUE,
 closure_request_id VARCHAR(36) UNIQUE,
 order_id BIGINT,
 payment_group_id BIGINT,
 reference_number VARCHAR(64),
 amount BIGINT CHECK(amount >= 0),
 reason VARCHAR(32),
 closed_at TIMESTAMP WITH TIME ZONE,
 CHECK ((closure_request_id IS NULL) = (closed_at IS NULL))
);
ALTER TABLE refund_execution ADD COLUMN order_closure_request_id VARCHAR(36);
CREATE UNIQUE INDEX uk_refund_closed_payment ON refund_execution(payment_id)
 WHERE order_closure_request_id IS NOT NULL;
