-- 좋아요 합계의 보관본. 실시간 합계는 Redis 에 있고 이 값은 주기적으로 뒤따라온다.
CREATE TABLE broadcast_like_snapshot (
    broadcast_id BIGINT PRIMARY KEY,
    total BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_broadcast_like_snapshot_broadcast
        FOREIGN KEY (broadcast_id) REFERENCES broadcast(id),
    CONSTRAINT ck_broadcast_like_snapshot_total CHECK (total >= 0)
);
