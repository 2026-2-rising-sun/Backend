-- 공개 환불 조회에 필요한 Payment 복구 정보의 사본만 보관한다.
ALTER TABLE refund_request ADD COLUMN retry_count SMALLINT NOT NULL DEFAULT 0,
 ADD COLUMN next_action_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
