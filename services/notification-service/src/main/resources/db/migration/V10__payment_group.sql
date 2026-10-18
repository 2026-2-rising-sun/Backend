-- =====================================================================
-- V10__payment_group.sql  (P3, #292)
--
-- 결제 묶음 사본이다. 묶음 하나에 행 하나다. 만료 임박 알림을 보낼 근거로만 쓴다.
--
-- 왜 사본을 두는가 — 만료 임박은 "지금 무슨 일이 일어났다" 가 아니라 "앞으로 일어날 일"
-- 이다. 이벤트가 오는 순간 보내면 너무 이르고, 만료된 뒤에 보내면 늦다. 보낼 시각의
-- 근거(expires_at)를 들고 있다가 그 시각이 되면 보내야 한다.
--
-- Commerce 에 물어보는 방식은 쓰지 않는다. 그러면 Commerce 가 죽었을 때 알림도 멈춘다.
-- 방송 시작 임박 알림(V3, V5)과 같은 구조다.
--
-- ⚠️ 알려진 제약 — 초기 적재가 없다.
--    알림 서비스가 구독을 시작한 뒤 생성된 묶음만 사본에 쌓인다. 그 전에 만들어진
--    묶음은 임박 알림을 받지 못한다. 묶음 수명이 15분이라 재기동 직후 잠깐만 해당된다.
--
-- group_number : 결제 묶음 번호. 메시지 key 이자 사본의 식별자다.
--                'PG-' + UUID(36) 이 가장 길어서 40 이면 충분하다.
-- product_summary : 알림 문구에 쓸 "가을 니트 외 2건". 이벤트의 orders 를 받을 때 한 번
--                   줄여서 저장한다. 주문 목록 전체를 들고 있을 이유가 없다.
-- closed_at    : 결제 완료, 주문 취소, 주문 만료로 끝난 시각. NULL 이면 아직 결제를
--                기다리는 중이다. 끝난 묶음에 "곧 만료됩니다" 를 보내면 안 된다.
--                결제 실패는 여기에 넣지 않는다. 만료 전까지 다시 시도할 수 있다.
-- expiring_soon_sent_at : 임박 알림을 보낸 시각. 보낸 뒤 채우므로 묶음당 한 번만 간다.
-- =====================================================================
CREATE TABLE payment_group (
    group_number          VARCHAR(40)  PRIMARY KEY,
    member_id             VARCHAR(36)  NOT NULL,
    product_summary       VARCHAR(160) NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at             TIMESTAMP WITH TIME ZONE,
    expiring_soon_sent_at TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 스케줄러의 조회 조건 그대로다 (아직 안 보냈고, 곧 만료되는 묶음).
-- closed_at 은 넣지 않았다. 이 인덱스로 좁혀진 뒤 걸러질 행은 "지금 5분 안에 만료되는
-- 묶음" 수준이라 적다. PostgreSQL 만 보면 부분 인덱스가 더 작지만 H2 가 못 만든다.
CREATE INDEX ix_payment_group_expiring_soon
    ON payment_group (expiring_soon_sent_at, expires_at);
