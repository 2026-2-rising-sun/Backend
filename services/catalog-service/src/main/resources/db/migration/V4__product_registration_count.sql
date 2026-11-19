ALTER TABLE product ADD COLUMN initial_sku_count INTEGER CHECK (initial_sku_count BETWEEN 1 AND 100);
