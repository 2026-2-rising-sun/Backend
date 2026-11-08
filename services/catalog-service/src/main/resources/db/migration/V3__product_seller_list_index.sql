CREATE INDEX ix_product_seller_created_at_id ON product (seller_id, created_at DESC, id DESC);
