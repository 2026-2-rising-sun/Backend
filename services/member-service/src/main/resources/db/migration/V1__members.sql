CREATE TABLE members (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_members_email UNIQUE (email),
    CONSTRAINT ck_members_email_normalized CHECK (email = LOWER(TRIM(email))),
    CONSTRAINT ck_members_role CHECK (role IN ('USER', 'ADMIN'))
);
