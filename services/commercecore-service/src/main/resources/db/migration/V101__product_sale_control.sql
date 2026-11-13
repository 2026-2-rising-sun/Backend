-- 기존 상품의 개별 판매 상태를 보존하고 신규 상품만 판매 준비 상태로 생성한다.
ALTER TABLE product ADD COLUMN sale_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE product ALTER COLUMN sale_enabled SET DEFAULT FALSE;
ALTER TABLE product ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE product ADD CONSTRAINT ck_product_deleted_sale_disabled
    CHECK (deleted_at IS NULL OR sale_enabled = FALSE);
ALTER TABLE product DROP CONSTRAINT fk_product_main_image;
