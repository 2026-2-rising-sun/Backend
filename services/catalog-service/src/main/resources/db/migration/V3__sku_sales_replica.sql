-- 코디네이터 기술 조정 V3. 상품당 한 행을 SKU(호환 필드명 sales_id)당 한 행으로 바꾼다.
-- 원본의 모든 값을 보존하며 잘못된 중복 sales_id는 PK 위반으로 중단한다.
ALTER TABLE product_sales RENAME TO product_sales_legacy;
CREATE TABLE product_sales (
    sales_id BIGINT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    price BIGINT NOT NULL CHECK (price >= 0),
    status VARCHAR(16) NOT NULL,
    available INTEGER NOT NULL CHECK (available >= 0),
    version BIGINT NOT NULL CHECK (version >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO product_sales(sales_id,product_id,price,status,available,version,updated_at)
SELECT sales_id,product_id,price,status,available,version,updated_at FROM product_sales_legacy;
DROP TABLE product_sales_legacy;
CREATE INDEX ix_product_sales_product_sku ON product_sales(product_id,sales_id);
