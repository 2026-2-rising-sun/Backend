-- =====================================================================
-- V4__product_sales.sql  (P3)
--
-- Commerce 판매정보 이벤트를 받아 두는 Shopping 쪽 read replica.
-- 원본은 commerce-service 의 sales_info 이며, 여기서는 읽기 전용 사본으로만 쓴다.
-- 시간 컬럼은 TIMESTAMP WITH TIME ZONE (Java Instant, UTC 저장).
-- =====================================================================


-- ----- product_sales -------------------------------------------------
-- product_id : 상품 id. 이벤트는 상품 등록과 독립적으로 도착할 수 있으므로 product 로의 FK 를 걸지 않는다.
--              FK 검사 실패가 판매정보 저장을 막으면 이벤트가 유실되므로 의도적으로 뺐다.
-- version    : Commerce 가 준 snapshot 버전. 더 큰 값만 반영해 중복·역순 이벤트를 무시한다.
-- status     : SalesStatus 이름 그대로 저장한다.
CREATE TABLE product_sales (
    product_id BIGINT       PRIMARY KEY,
    sales_id   BIGINT       NOT NULL,
    price      BIGINT       NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    available  INTEGER      NOT NULL,
    version    BIGINT       NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_product_sales_price_non_negative CHECK (price >= 0),
    CONSTRAINT ck_product_sales_available_non_negative CHECK (available >= 0),
    CONSTRAINT ck_product_sales_version_non_negative CHECK (version >= 0)
);
