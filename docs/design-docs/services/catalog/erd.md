# Catalog DB ERD

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| product | PK id; name; main_image_id/url; seller_id; category; version | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) |
| product_sales | PK product_id; sales_id; price; status; available; version | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) |
| coupon_display | PK coupon_id; fixed_discount; sold_out; version; 유효 기간 | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) |
| coupon_display_product | PK (coupon_id,product_id) | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| coupon_display_product.coupon_id | coupon_display.coupon_id | 물리 FK · ON DELETE CASCADE |
| product_sales.product_id | product.id | 논리 참조 · 이벤트 도착 순서 차이, FK 없음 |
| coupon_display_product.product_id | product.id | 논리 참조 · FK 없음 |
| product.id | Core.product.id | 서비스 간 원본 ID · FK 없음 |

## 제약과 이관

조회 사본은 이벤트 도착 순서가 다를 수 있어 product와 product_sales 사이 FK가 없다. 상품 version은 0 이상, 판매 backfill은 version 0, 쿠폰 display version은 1 이상이다.

## 원본과 매핑

- [V1__catalog_baseline.sql](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql)

JPA 매핑: [Product.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/product/entity/Product.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
