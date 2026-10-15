-- =====================================================================
-- V5__broadcast_starting_soon.sql  (P3)
--
-- 방송 시작 임박 알림을 한 방송에 한 번만 보내기 위한 표시다.
--
-- 왜 예약 테이블을 따로 두지 않는가 — 보낼 시각의 근거(scheduled_at)가 이미 이 표에 있다.
-- "언제 보낼지" 를 다시 적어 두면 일정이 바뀔 때 두 곳을 맞춰야 한다.
-- 스케줄러가 이 표를 직접 훑어 "곧 시작하는데 아직 안 보낸 방송" 을 찾는다.
-- =====================================================================

-- 임박 알림을 보낸 시각. NULL 이면 아직 안 보냈다.
-- 보낸 뒤 이 값을 채우므로, 일정이 바뀌어도 임박 알림은 방송당 한 번이다.
ALTER TABLE broadcast_schedule ADD COLUMN starting_soon_sent_at TIMESTAMP WITH TIME ZONE;

-- 스케줄러의 조회 조건 그대로다 (아직 안 보냈고, 곧 시작하는 방송).
-- PostgreSQL 만 보면 "WHERE starting_soon_sent_at IS NULL" 부분 인덱스가 더 작지만,
-- 테스트가 H2 로 같은 마이그레이션을 돌리고 H2 는 부분 인덱스를 못 만든다.
CREATE INDEX ix_broadcast_schedule_starting_soon
    ON broadcast_schedule (starting_soon_sent_at, scheduled_at);
