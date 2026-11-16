-- 빈 Catalog DB의 조회 사본. ID는 Core에서 받고 원본/이미지 파일/멱등키는 두지 않는다.
CREATE TABLE product (
 id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(2000) NOT NULL,
 main_image_id BIGINT NOT NULL, main_image_url VARCHAR(2048) NOT NULL, seller_id VARCHAR(36),
 category VARCHAR(32), version BIGINT NOT NULL CHECK (version >= 0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_product_created_at_id ON product (created_at DESC,id DESC);
CREATE INDEX ix_product_category_created_at_id ON product (category,created_at DESC,id DESC);
-- =====================================================================
-- V4__product_sales.sql  (P3)
--
-- Commerce 판매정보 이벤트를 받아 두는 Catalog 쪽 read replica.
-- 원본은 commercecore-service 의 sales_info 이며, 여기서는 읽기 전용 사본으로만 쓴다.
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
-- =====================================================================
-- V5__coupon_display.sql
--
-- Commerce 쿠폰 표시정보 이벤트(commerce.coupon-info.v1)를 받아 두는 Catalog 쪽 replica.
-- 원본은 commercecore-service 의 쿠폰이며, 여기서는 상품 화면에 쿠폰을 보여 주는 용도로만 쓴다.
-- 회원별 발급·사용 이력은 두지 않는다. 받기와 최종 적용 판정은 Commerce 가 한다.
-- 시간 컬럼은 TIMESTAMP WITH TIME ZONE (Java Instant, UTC 저장).
-- =====================================================================


-- ----- coupon_display ------------------------------------------------
-- coupon_id : Commerce 쿠폰 ID. UUID 로 강제하지 않는 문자열이다 (계약상 최대 36자).
-- starts_at : 발급 시작(포함), ends_at : 발급 종료(제외). 기간 경계 이벤트가 없어 조회 시 비교한다.
-- version   : Commerce 가 준 snapshotVersion. 더 큰 값만 반영해 중복·역순 이벤트를 무시한다.
CREATE TABLE coupon_display (
    coupon_id      VARCHAR(36)  PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    fixed_discount BIGINT       NOT NULL,
    starts_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    sold_out       BOOLEAN      NOT NULL,
    version        BIGINT       NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_coupon_display_discount_positive CHECK (fixed_discount >= 1),
    CONSTRAINT ck_coupon_display_version_positive CHECK (version >= 1)
);


-- ----- coupon_display_product ----------------------------------------
-- 쿠폰과 대상 상품의 연결. 쿠폰이 바뀌면 그 쿠폰의 연결을 통째로 바꾼다.
-- product_id 에 product 로의 FK 를 걸지 않는다. 쿠폰 이벤트가 상품보다 먼저 와도 저장이 실패하지 않게 한다.
CREATE TABLE coupon_display_product (
    coupon_id  VARCHAR(36) NOT NULL REFERENCES coupon_display (coupon_id) ON DELETE CASCADE,
    product_id BIGINT      NOT NULL,
    PRIMARY KEY (coupon_id, product_id)
);

-- 상품 화면은 상품 ID 로 쿠폰을 찾는다.
CREATE INDEX ix_coupon_display_product_product ON coupon_display_product (product_id);

CREATE INDEX ix_product_sales_status_price_id ON product_sales (status,price,product_id);
CREATE INDEX ix_product_sales_price_id ON product_sales (price,product_id);
