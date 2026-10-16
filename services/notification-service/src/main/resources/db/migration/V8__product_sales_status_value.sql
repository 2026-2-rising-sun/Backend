-- =====================================================================
-- V8__product_sales_status_value.sql  (P3, #264)
--
-- 판매 상태를 참거짓(in_stock) 대신 받은 상태 값 그대로 저장한다.
--
-- V6 이 상태를 "판매 중인가" 참거짓으로 줄였는데, 그러면 판매 중이 아닌 상태가
-- 모두 한 값으로 뭉친다. SOLD_OUT 과 READY, PRIVATE 가 구분되지 않아
-- 아래 두 가지가 틀린다.
--
--   1. 재입고 판별 — 재입고는 SOLD_OUT 에서 ON_SALE 로 바뀐 것이다. 참거짓으로는
--      판매 준비(READY)에서 판매 시작으로 바뀐 것도 재입고로 보인다. 품절된 적이
--      없는 상품에 "재입고되었습니다" 가 간다.
--   2. 신청 거절 — 재입고 알림은 품절을 전제한다. 참거짓으로는 판매 중만 거절하므로
--      판매 준비(READY)나 비공개(PRIVATE) 상품에 신청이 통과한다.
--
-- raw_status 에 이미 원본이 들어 있으므로 값은 그대로 옮긴다. 컬럼 이름만 바뀐다.
-- RENAME COLUMN 은 PostgreSQL 과 H2 의 문법이 달라 추가, 복사, 삭제로 쓴다.
-- =====================================================================

-- 받은 상태 값 원본. 이제 판단에 쓰는 값이다.
-- 모르는 값이 와도 거부하지 않는다. SOLD_OUT 도 ON_SALE 도 아니면 둘 중 어느 판단에도
-- 걸리지 않아서, Commerce 가 상태를 추가해도 이쪽이 틀리지 않는다.
ALTER TABLE product_sales_status ADD COLUMN status VARCHAR(16);
UPDATE product_sales_status SET status = raw_status;
ALTER TABLE product_sales_status ALTER COLUMN status SET NOT NULL;

ALTER TABLE product_sales_status DROP COLUMN raw_status;
ALTER TABLE product_sales_status DROP COLUMN in_stock;
