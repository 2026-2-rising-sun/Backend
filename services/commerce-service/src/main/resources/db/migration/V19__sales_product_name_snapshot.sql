-- 기존 판매정보는 이름 없이 유지하고 신규 등록부터 상품 이름 사본을 보존한다.
ALTER TABLE sales_info ADD COLUMN product_name_snapshot VARCHAR(255) NOT NULL DEFAULT '';
