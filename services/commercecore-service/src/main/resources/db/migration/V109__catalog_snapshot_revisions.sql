-- 메타데이터와 재고의 revision·시각은 독립적이며 같은 revision 재적재에서도 원문을 유지한다.
ALTER TABLE sku ADD COLUMN metadata_version BIGINT NOT NULL DEFAULT 0 CHECK (metadata_version>=0);
ALTER TABLE sku ADD COLUMN metadata_product_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sku ADD COLUMN inventory_version BIGINT NOT NULL DEFAULT 0 CHECK (inventory_version>=0);
ALTER TABLE sku ADD COLUMN inventory_published_version BIGINT NOT NULL DEFAULT -1;
ALTER TABLE sku ADD COLUMN metadata_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE sku ADD COLUMN inventory_changed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE sku ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
UPDATE sku SET metadata_updated_at=updated_at,inventory_changed_at=updated_at,
    metadata_product_version=COALESCE((SELECT version FROM product WHERE product.id=sku.product_id),0);
ALTER TABLE sku ALTER COLUMN metadata_updated_at SET NOT NULL;
ALTER TABLE sku ALTER COLUMN metadata_updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE sku ALTER COLUMN inventory_changed_at SET NOT NULL;
ALTER TABLE sku ALTER COLUMN inventory_changed_at SET DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX ix_sku_inventory_dirty ON sku(inventory_published_version,inventory_version,id);
