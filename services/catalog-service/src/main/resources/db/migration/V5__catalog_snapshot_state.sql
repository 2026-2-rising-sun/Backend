-- 상품/SKU 선도착과 독립 버전 원문을 보존한다. tombstone은 삭제하지 않는다.
CREATE TABLE catalog_snapshot_state (
 aggregate_id VARCHAR(100) PRIMARY KEY,
 metadata_version BIGINT NOT NULL DEFAULT -1,
 inventory_version BIGINT NOT NULL DEFAULT -1,
 metadata_payload TEXT,
 inventory_payload TEXT,
 deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- eventId 재사용은 원문과 대조하며 수신 기록과 읽기 모델 변경을 함께 커밋한다.
CREATE TABLE catalog_snapshot_receipt (
 event_id VARCHAR(36) PRIMARY KEY,
 envelope TEXT NOT NULL
);
