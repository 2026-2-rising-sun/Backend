-- =====================================================================
-- V6__product_sales_status.sql  (P3)
--
-- Commerce 판매정보 중 알림이 쓰는 부분만 담은 사본이다. 상품 하나에 행 하나다.
--
-- 왜 사본을 두는가 — 재입고는 "품절에서 판매중으로 바뀌었다" 는 전이다. 그런데 이벤트에는
-- 바뀐 뒤의 상태만 담겨 있고 이전 상태가 없다. 마지막 상태를 우리가 들고 있어야 전이를 알 수 있다.
-- Commerce 에 그때그때 물어보는 방식은 쓰지 않는다. 그러면 Commerce 가 죽었을 때 알림도 멈춘다.
--
-- ⚠️ 알려진 제약 — 초기 적재가 없다.
--    사본은 알림 서비스가 구독을 시작한 뒤의 이벤트로만 쌓인다. Kafka 보관 기간보다 전에
--    품절된 상품은 사본에 없고, 그 상품에는 재입고 신청이 404 로 거절된다.
--    Commerce 조회 API 로 초기 적재를 하면 해결되지만 HTTP 의존이 생긴다.
--    재입고가 3차 기능이라 지금은 제약으로 두고, 필요해지면 적재를 추가한다.
--
-- in_stock   : 판매 중인지.
-- raw_status : 받은 값 원본.
--              ⚠️ 이 두 컬럼은 V8 에서 status 하나로 바뀐다. 참거짓으로 줄이면 품절과
--              판매 준비가 같은 값이 되어 재입고 판별이 틀렸다. 자세한 이유는 V8 에 적었다.
--
-- snapshot_version : 이벤트 시점의 스냅샷 버전. Kafka 는 같은 메시지를 두 번 보낼 수 있고
--                    순서가 뒤바뀔 수도 있어서, 저장된 값보다 큰 버전만 반영한다.
-- =====================================================================
CREATE TABLE product_sales_status (
    product_id       BIGINT      PRIMARY KEY,
    sales_id         BIGINT      NOT NULL,
    snapshot_version BIGINT      NOT NULL,
    in_stock         BOOLEAN     NOT NULL,
    raw_status       VARCHAR(16) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
