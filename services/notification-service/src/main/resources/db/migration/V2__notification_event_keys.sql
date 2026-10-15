-- =====================================================================
-- V2__notification_event_keys.sql  (P3)
--
-- 이벤트를 구독해 알림을 만들기 시작하면서 필요해진 컬럼이다.
-- V1 주석에 "이벤트 계약이 확정된 뒤에 추가한다" 고 남겨둔 것들이다.
--
-- 이 테이블은 아직 비어 있다 — 알림을 만드는 코드가 없었기 때문이다. 그래서 NOT NULL 로 바로 넣는다.
-- 수동 테스트로 넣어둔 행이 있으면 비우고 적용해야 한다.
-- =====================================================================

-- event_id   : 이 알림을 만들게 한 이벤트의 ID (봉투의 eventId, UUID 문자열).
--              아래 유일 제약이 중복 알림 방어의 전부다. Kafka 는 같은 메시지를 두 번 보낼 수 있고,
--              한 이벤트로 여러 명에게 알림을 만드는 경우(방송·재입고)도 같은 규칙 하나로 처리된다.
-- event_type : 어떤 이벤트에서 왔는지(LiveBroadcastStarted 등). 화면이 아이콘을 고르는 근거다.
--              모르는 값이 와도 알림 서비스가 멈추면 안 되므로 enum 이 아니라 문자열로 둔다.
-- link_type,
-- link_value : 알림을 눌렀을 때 보낼 곳. 보낼 곳이 없는 알림도 있어서 둘 다 NULL 을 허용한다.
--              알림 서비스는 다른 도메인의 URL 을 모른다. 종류와 값만 주고 화면이 판단한다.
ALTER TABLE notification ADD COLUMN event_id VARCHAR(36) NOT NULL;
ALTER TABLE notification ADD COLUMN event_type VARCHAR(64) NOT NULL;
ALTER TABLE notification ADD COLUMN link_type VARCHAR(32);
ALTER TABLE notification ADD COLUMN link_value VARCHAR(100);

ALTER TABLE notification
    ADD CONSTRAINT uk_notification_event_member UNIQUE (event_id, member_id);
