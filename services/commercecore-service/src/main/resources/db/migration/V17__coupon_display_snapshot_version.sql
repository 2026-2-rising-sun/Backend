-- 표시 상태 버전은 기존 판매자 설정 낙관락 version과 독립적으로 증가한다.
ALTER TABLE coupon_definition ADD COLUMN snapshot_version BIGINT NOT NULL DEFAULT 1 CHECK (snapshot_version >= 1);
