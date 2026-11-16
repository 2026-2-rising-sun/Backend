CREATE TABLE member_coupon (
    id VARCHAR(36) PRIMARY KEY,
    coupon_id VARCHAR(36) NOT NULL REFERENCES coupon_definition(id),
    member_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('AVAILABLE','RESERVED','USED')),
    claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(coupon_id, member_id)
);
CREATE INDEX ix_member_coupon_member_claimed ON member_coupon(member_id, claimed_at, id);
