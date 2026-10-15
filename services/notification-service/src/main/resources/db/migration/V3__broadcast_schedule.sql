-- =====================================================================
-- V3__broadcast_schedule.sql  (P3)
--
-- Live 가 발행하는 방송 일정의 조회용 사본이다. 방송 하나에 행 하나다.
--
-- 왜 사본을 두는가 — 알림이 Live 에 HTTP 로 물어보지 않기 위해서다. 물어보는 구조면
-- Live 가 죽었을 때 알림 신청도 못 받고, 시작 임박 알림 시각도 계산할 수 없다.
-- Live 의 LiveBroadcastScheduled payload 주석에도 "알림 서비스는 broadcastId 로 upsert 해서
-- 시작 임박 알림 시각을 scheduledAt 으로 계산한다" 고 적혀 있다.
--
-- broadcast_version : 이벤트 시점의 방송 version. Kafka 는 같은 메시지를 두 번 보낼 수 있고
--                     순서가 뒤바뀔 수도 있어서, 저장된 값보다 큰 version 만 반영한다.
--                     그러면 중복 이벤트는 0건 갱신으로 끝나고, 늦게 온 옛 이벤트가 새 일정을 덮지 않는다.
-- title             : 알림 문구에 쓴다. Live 의 제목 길이 제한과 같다.
-- scheduled_at      : 방송 예정 시각. 시작 임박 알림을 보낼 시각의 계산 근거다.
-- =====================================================================
CREATE TABLE broadcast_schedule (
    broadcast_id      BIGINT       PRIMARY KEY,
    broadcast_version BIGINT       NOT NULL,
    title             VARCHAR(100) NOT NULL,
    scheduled_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
