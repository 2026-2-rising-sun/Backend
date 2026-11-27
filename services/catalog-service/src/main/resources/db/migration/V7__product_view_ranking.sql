-- 상품 상세 조회수. 앱 메모리에 모은 조회를 1분마다 시간 단위로 더한다. 앱이 여러 대면 같은 칸에 함께 더한다.
-- 랭킹 계산이 집계 기간(7일)보다 오래된 칸을 지운다.
CREATE TABLE product_view_hourly (
    product_id BIGINT NOT NULL,
    hour_start TIMESTAMP WITH TIME ZONE NOT NULL,
    views BIGINT NOT NULL CHECK (views >= 0),
    PRIMARY KEY (product_id, hour_start)
);

-- 랭킹 점수. 1시간마다 최근 7일 조회수 합으로 다시 계산한다. 점수가 0이 된 상품은 지운다.
-- 점수가 바뀐 상품은 같은 트랜잭션에서 index_task 를 남겨 ES 문서의 viewScore 를 고친다.
CREATE TABLE product_view_score (
    product_id BIGINT PRIMARY KEY,
    score BIGINT NOT NULL CHECK (score > 0)
);
