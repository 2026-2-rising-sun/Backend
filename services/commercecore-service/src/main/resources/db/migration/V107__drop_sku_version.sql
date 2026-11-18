-- SKU 변경은 행 잠금과 조건부 UPDATE로 보호한다. 이벤트 최신성용 snapshot_version은 유지한다.
ALTER TABLE sku DROP COLUMN version;
