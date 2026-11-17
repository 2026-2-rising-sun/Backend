-- 기존 사본은 현재 개별 판매 상태를 유지하며 새 이벤트의 전체 제어를 함께 보관한다.
ALTER TABLE product ADD COLUMN sale_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE product ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE product ADD CONSTRAINT ck_product_deleted_sale_disabled
    CHECK (deleted_at IS NULL OR sale_enabled = FALSE);
