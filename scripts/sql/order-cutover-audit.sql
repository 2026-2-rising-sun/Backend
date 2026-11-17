-- Core V106까지 기술적 이행을 검증한 복제 DB에서 실행한다. 운영 이행·기한 보정·금전 재실행을 수행하지 않는다.
-- 결과가 0이어도 Payment 대사와 체크섬·백업 검토를 대신하지 않는다.
BEGIN TRANSACTION READ ONLY;
SELECT version, checksum, success FROM flyway_schema_history WHERE version IN ('104','105','106') ORDER BY installed_rank;
SELECT g.id,g.group_number,g.policy_version,g.status,g.payment_id,g.expires_at,g.processing_expires_at,
       (g.status='PAYMENT_CONFIRMING' AND g.processing_expires_at IS NULL) AS missing_processing_deadline,
       (g.policy_version>0 AND r.payment_group_id IS NULL) AS missing_payment_binding
FROM payment_group g LEFT JOIN order_payment_relay r ON r.payment_group_id=g.id
WHERE g.status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING')
  AND ((g.status='PAYMENT_CONFIRMING' AND g.processing_expires_at IS NULL)
    OR (g.policy_version>0 AND r.payment_group_id IS NULL)) ORDER BY g.id;
-- 전달 원문·주문 번호는 갱신하지 않는다. 원문에 포함될 수 있는 결제 키를 출력하지 않고 해시로 대조한다.
SELECT request_id,kind,payment_group_id,reference_number,payment_id,amount,status,md5(command) AS command_hash
FROM payment_delivery_request WHERE kind IN ('PAYMENT','REFUND') AND status IN ('PROCESSING','UNKNOWN') ORDER BY requested_at,request_id;
-- 과거 추가 테이블/열은 지우지 않는다. 존재하면 별도 Payment 대사 대상이다.
SELECT to_regclass('order_payment_closure') IS NOT NULL AS archived_closure_table_present;
SELECT column_name FROM information_schema.columns
WHERE table_schema=current_schema() AND table_name='refund_request'
  AND column_name IN ('purpose','order_closure_request_id') ORDER BY column_name;
ROLLBACK;
