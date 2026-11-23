-- 보상 대기 주문은 회원의 새 구매를 막지 않는다. 결제 대기·확인 중 주문만 회원당 1건이다.
DROP INDEX uk_payment_group_active_member;
CREATE UNIQUE INDEX uk_payment_group_active_member ON payment_group(member_id) WHERE status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING');
