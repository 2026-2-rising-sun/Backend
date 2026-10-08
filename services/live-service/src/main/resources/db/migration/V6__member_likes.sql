-- local/dev policy transition: previous anonymous click totals are deliberately not imported.
-- V5 snapshots remain as legacy data; all current reads and writes use these tables.
CREATE TABLE broadcast_like_aggregate (
    broadcast_id BIGINT PRIMARY KEY REFERENCES broadcast(id),
    total BIGINT NOT NULL DEFAULT 0 CHECK (total >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE broadcast_member_like (
    broadcast_id BIGINT NOT NULL REFERENCES broadcast(id),
    member_id VARCHAR(36) NOT NULL,
    liked BOOLEAN NOT NULL,
    state_version BIGINT NOT NULL CHECK (state_version >= 0),
    PRIMARY KEY (broadcast_id, member_id)
);
-- Retain request results for the lifetime of the broadcast, including cancelled likes.
CREATE TABLE broadcast_like_request (
    broadcast_id BIGINT NOT NULL REFERENCES broadcast(id),
    member_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    desired BOOLEAN NOT NULL,
    liked BOOLEAN NOT NULL,
    state_version BIGINT NOT NULL,
    total BIGINT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (broadcast_id, member_id, request_id),
    CHECK (state_version >= 0 AND total >= 0 AND version >= 0)
);
