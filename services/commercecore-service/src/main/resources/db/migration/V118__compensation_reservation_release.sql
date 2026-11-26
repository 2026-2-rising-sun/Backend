-- 보상 대기로 바꾸는 트랜잭션에서 재고·쿠폰 예약을 해제한 시각. 값이 없는 보상 대기 주문(배포 전 행)은 보상 완료 때 한 번 해제한다.
ALTER TABLE payment_group ADD COLUMN compensation_released_at TIMESTAMPTZ;
