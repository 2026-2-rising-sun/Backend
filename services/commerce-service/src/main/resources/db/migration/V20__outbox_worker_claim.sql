ALTER TABLE event_outbox
    ADD COLUMN claim_token VARCHAR(36),
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE event_outbox ADD CONSTRAINT ck_event_outbox_claim
    CHECK ((claim_token IS NULL) = (lease_until IS NULL));

CREATE INDEX ix_event_outbox_pending_key
    ON event_outbox(topic, message_key, id) WHERE published_at IS NULL;
CREATE INDEX ix_event_outbox_pending_retry
    ON event_outbox(next_attempt_at, id) WHERE published_at IS NULL;
