-- 기존 구매의 PK·금액·Cart 출처·TTL은 그대로 두고 새 주문서에만 policy_version=1을 사용한다.
ALTER TABLE payment_group ADD COLUMN policy_version SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE payment_group ADD COLUMN failure_reason VARCHAR(32);
ALTER TABLE payment_group ADD COLUMN coupon_order_item_id BIGINT;
ALTER TABLE member_coupon ADD COLUMN used_payment_group_id BIGINT;
ALTER TABLE orders ADD COLUMN sku_id BIGINT;
ALTER TABLE orders ADD COLUMN product_id_snapshot BIGINT;
ALTER TABLE orders ADD COLUMN seller_id_snapshot VARCHAR(36);
ALTER TABLE orders ADD COLUMN main_image_snapshot VARCHAR(2048);
ALTER TABLE orders ADD COLUMN selected_options_snapshot TEXT;
ALTER TABLE orders ALTER COLUMN sales_info_id DROP NOT NULL;
ALTER TABLE orders ADD CONSTRAINT ck_orders_trade_source CHECK (sales_info_id IS NOT NULL OR sku_id IS NOT NULL);
CREATE INDEX ix_orders_sku ON orders(sku_id);
-- Sales와 기본 SKU의 검증된 ID 보존 매핑만 사용한다. 과거 옵션·금액은 새 값으로 채우지 않는다.
UPDATE orders o SET sku_id=s.id,product_id_snapshot=s.product_id
 FROM sales_info s JOIN sku k ON k.id=s.id WHERE o.sales_info_id=s.id;
ALTER TABLE refund_request ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'USER';
ALTER TABLE refund_request ADD COLUMN order_closure_request_id VARCHAR(36);
CREATE UNIQUE INDEX uk_refund_order_closure ON refund_request(order_closure_request_id)
 WHERE order_closure_request_id IS NOT NULL;
CREATE TABLE order_payment_closure (
 closure_request_id VARCHAR(36) PRIMARY KEY,
 payment_request_id VARCHAR(36) NOT NULL UNIQUE,
 payment_id BIGINT NOT NULL UNIQUE,
 order_id BIGINT NOT NULL,
 payment_group_id BIGINT NOT NULL,
 reference_number VARCHAR(64) NOT NULL,
 amount BIGINT NOT NULL CHECK(amount>=0),
 reason VARCHAR(32) NOT NULL CHECK(reason='EXPIRED'),
 closed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
 next_action_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_order_closure_due ON order_payment_closure(acknowledged,next_action_at);

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK(status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED'));
ALTER TABLE payment_group DROP CONSTRAINT payment_group_status_check;
ALTER TABLE payment_group ADD CONSTRAINT payment_group_status_check CHECK(status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING','PAID','FAILED','CANCELLED','EXPIRED','REFUNDED','PARTIALLY_REFUNDED'));
ALTER TABLE refund_target_order ADD COLUMN quantity INTEGER CHECK(quantity>0);
UPDATE refund_target_order t SET quantity=o.quantity FROM orders o WHERE o.id=t.order_id;
