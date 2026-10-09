ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (
    status IN (
        'PENDING_PAYMENT', 'PAYMENT_CONFIRMING', 'PAID', 'FAILED',
        'CANCELLED', 'EXPIRED', 'REFUNDED'
    )
);

ALTER TABLE payment_group DROP CONSTRAINT payment_group_status_check;
ALTER TABLE payment_group ADD CONSTRAINT payment_group_status_check CHECK (
    status IN (
        'PENDING_PAYMENT', 'PAYMENT_CONFIRMING', 'PAID', 'FAILED',
        'CANCELLED', 'EXPIRED', 'REFUNDED'
    )
);
