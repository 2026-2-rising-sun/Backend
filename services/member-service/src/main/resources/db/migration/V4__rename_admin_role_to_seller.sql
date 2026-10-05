-- Coordinated role transition: old ADMIN access/refresh credentials require sign-in again.
UPDATE refresh_families SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
    security_revoked_at = COALESCE(security_revoked_at, CURRENT_TIMESTAMP)
WHERE member_id IN (SELECT id FROM members WHERE role = 'ADMIN');

ALTER TABLE members DROP CONSTRAINT ck_members_role;
UPDATE members SET role = 'SELLER', updated_at = CURRENT_TIMESTAMP, version = version + 1
WHERE role = 'ADMIN';
ALTER TABLE members ADD CONSTRAINT ck_members_role CHECK (role IN ('USER', 'SELLER'));
