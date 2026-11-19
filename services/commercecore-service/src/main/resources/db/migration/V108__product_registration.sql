ALTER TABLE product ADD COLUMN initial_sku_count INTEGER CHECK (initial_sku_count BETWEEN 1 AND 100);
ALTER TABLE sku ADD COLUMN option_label VARCHAR(255);
CREATE TABLE product_registration (
    seller_id VARCHAR(36) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    storage_key VARCHAR(128) NOT NULL UNIQUE,
    response_json TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (seller_id, request_key)
);
CREATE INDEX ix_product_registration_pending ON product_registration(updated_at) WHERE response_json IS NULL;
