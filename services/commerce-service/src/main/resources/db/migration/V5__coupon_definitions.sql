CREATE TABLE coupon_definition (
    id VARCHAR(36) PRIMARY KEY,
    seller_id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    fixed_discount BIGINT NOT NULL CHECK (fixed_discount > 0),
    issuance_limit INTEGER NOT NULL CHECK (issuance_limit BETWEEN 1 AND 10000),
    issued_count INTEGER NOT NULL DEFAULT 0 CHECK (issued_count >= 0 AND issued_count <= issuance_limit),
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (starts_at < ends_at AND ends_at <= expires_at)
);
CREATE INDEX ix_coupon_seller_created ON coupon_definition(seller_id, created_at, id);
CREATE TABLE coupon_target (
    coupon_id VARCHAR(36) NOT NULL REFERENCES coupon_definition(id),
    product_id BIGINT NOT NULL CHECK (product_id > 0),
    PRIMARY KEY(coupon_id, product_id)
);
