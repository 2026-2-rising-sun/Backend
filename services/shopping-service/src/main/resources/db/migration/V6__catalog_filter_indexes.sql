-- 판매정보 이벤트 사본의 상태·가격 정렬과 같은 가격의 안정적인 ID 순서를 지원한다.
CREATE INDEX ix_product_sales_status_price_id ON product_sales (status, price, product_id);
CREATE INDEX ix_product_sales_price_id ON product_sales (price, product_id);
