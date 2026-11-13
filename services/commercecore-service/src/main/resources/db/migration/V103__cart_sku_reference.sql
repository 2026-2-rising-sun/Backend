-- 기존 ID·버전·수량·감사 시각은 결제 결과와 과거 환불의 출처 식별에 사용한다.
ALTER TABLE cart_item ADD COLUMN sku_id BIGINT;

-- 상품의 임의 SKU 대신 V102에서 보존한 기존 Sales ID만 기본 SKU로 연결한다.
UPDATE cart_item c
SET sku_id = legacy.id
FROM sales_info legacy
JOIN sku s ON s.id = legacy.id AND s.product_id = legacy.product_id
WHERE c.product_id = legacy.product_id;

-- 판매정보가 없었던 행은 product_id와 NULL sku_id를 유지해 이용 불가 항목으로 표시한다.
ALTER TABLE cart_item DROP CONSTRAINT uk_cart_member_product;
ALTER TABLE cart_item ADD CONSTRAINT uk_cart_member_sku UNIQUE (member_id, sku_id);
ALTER TABLE cart_item ADD CONSTRAINT ck_cart_sku CHECK (sku_id IS NULL OR sku_id > 0);
CREATE UNIQUE INDEX uk_cart_member_legacy_product
    ON cart_item (member_id, product_id) WHERE sku_id IS NULL;
