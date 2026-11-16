-- 기존 생성 기한·진행 중 데이터는 재해석하지 않는다. 새 승인 실행만 별도 기한을 저장한다.
ALTER TABLE payment_group ADD COLUMN processing_expires_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_payment_group_processing_expiry ON payment_group(processing_expires_at)
 WHERE status='PAYMENT_CONFIRMING';

-- Payment 환불 계약·소비자 구현 전에도 늦은 승인에 대한 후속 조치를 잃지 않는다.
CREATE TABLE late_approval_refund (
    payment_id BIGINT PRIMARY KEY,
    payment_group_id BIGINT NOT NULL REFERENCES payment_group(id),
    refund_request_id VARCHAR(64) NOT NULL UNIQUE,
    order_ref VARCHAR(64) NOT NULL,
    amount BIGINT NOT NULL CHECK(amount >= 0),
    status VARCHAR(32) NOT NULL CHECK(status IN ('REQUIRED','REQUESTED','SUCCEEDED','FAILED')),
    approved_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
