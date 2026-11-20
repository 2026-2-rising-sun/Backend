-- V115 적용 DB도 검증된 시스템 보상 완료 전까지 예약을 보존한다.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK(status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','COMPENSATION_PENDING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED','CANCEL_PENDING'));
ALTER TABLE payment_group DROP CONSTRAINT payment_group_status_check;
ALTER TABLE payment_group ADD CONSTRAINT payment_group_status_check CHECK(status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','COMPENSATION_PENDING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED','CANCEL_PENDING'));
-- 이미 접수된 미완료 보상은 같은 원문/ID로 계속한다. 종료된 과거 주문은 유지한다.
UPDATE orders o SET status='COMPENSATION_PENDING', version=o.version+1, updated_at=CURRENT_TIMESTAMP
 FROM payment_group g
 WHERE o.payment_group_id=g.id AND o.status=g.status
 AND g.policy_version=1 AND g.status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING')
 AND EXISTS (SELECT 1 FROM core_refund_intent i WHERE i.payment_group_id=g.id AND i.purpose='SYSTEM_COMPENSATION' AND i.status<>'COMPLETED');
UPDATE payment_group g SET status='COMPENSATION_PENDING', version=version+1
 WHERE g.policy_version=1 AND g.status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING')
 AND EXISTS (SELECT 1 FROM core_refund_intent i WHERE i.payment_group_id=g.id AND i.purpose='SYSTEM_COMPENSATION' AND i.status<>'COMPLETED');

-- 보상 대기 동안에도 회원당 활성 주문은 하나다.
DROP INDEX uk_payment_group_active_member;
CREATE UNIQUE INDEX uk_payment_group_active_member ON payment_group(member_id) WHERE status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','COMPENSATION_PENDING');
