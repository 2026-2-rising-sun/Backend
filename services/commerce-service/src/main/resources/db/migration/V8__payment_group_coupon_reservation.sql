ALTER TABLE payment_group
    ADD COLUMN coupon_id VARCHAR(36) REFERENCES coupon_definition(id);
