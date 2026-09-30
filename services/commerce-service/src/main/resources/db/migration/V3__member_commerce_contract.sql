-- Never silently delete or assign an owner to legacy guest orders.
-- A legacy DB must stop at V2 and follow ops/member-transition.md before advancing.
ALTER TABLE orders ALTER COLUMN member_id SET NOT NULL;
ALTER TABLE orders DROP COLUMN lookup_password_hash;
