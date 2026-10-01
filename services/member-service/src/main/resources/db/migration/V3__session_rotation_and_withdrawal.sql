ALTER TABLE members ADD COLUMN withdrawn_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE refresh_families ADD COLUMN security_revoked_at TIMESTAMP WITH TIME ZONE;

-- Pre-V3 access tokens lack sid. Invalidate their refresh families as well: sign in again.
-- Preserve hashes and member data; this migration does not delete personal information.
UPDATE refresh_families SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
    security_revoked_at = CURRENT_TIMESTAMP;
