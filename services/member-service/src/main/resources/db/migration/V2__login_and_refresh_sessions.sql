ALTER TABLE members ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE members ADD COLUMN login_locked_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE members ADD CONSTRAINT ck_members_failed_logins CHECK (failed_login_attempts >= 0);

CREATE TABLE refresh_families (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES members(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_refresh_family_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_refresh_families_member ON refresh_families(member_id);

CREATE TABLE refresh_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES refresh_families(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_refresh_token_hash_length CHECK (LENGTH(token_hash) = 64)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens(family_id);
