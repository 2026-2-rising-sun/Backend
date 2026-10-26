-- 기존 상품은 미분류(NULL)로 둔다. 허용 값은 애플리케이션 enum 이 검증한다.
ALTER TABLE product ADD COLUMN category VARCHAR(32);
CREATE INDEX ix_product_category_created_at_id ON product (category, created_at DESC, id DESC);
