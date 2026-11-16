ALTER TABLE orders ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN payable_amount BIGINT;
UPDATE orders SET payable_amount = total_amount;
ALTER TABLE orders ALTER COLUMN payable_amount SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT ck_orders_discount_amount
    CHECK (discount_amount >= 0 AND discount_amount <= total_amount);
ALTER TABLE orders ADD CONSTRAINT ck_orders_payable_amount
    CHECK (payable_amount = total_amount - discount_amount);

ALTER TABLE payment_group ADD COLUMN discount_amount BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_group ADD COLUMN payable_amount BIGINT;
UPDATE payment_group SET payable_amount = total_amount;
ALTER TABLE payment_group ALTER COLUMN payable_amount SET NOT NULL;
ALTER TABLE payment_group ADD CONSTRAINT ck_payment_group_discount_amount
    CHECK (discount_amount >= 0 AND discount_amount <= total_amount);
ALTER TABLE payment_group ADD CONSTRAINT ck_payment_group_payable_amount
    CHECK (payable_amount = total_amount - discount_amount);
