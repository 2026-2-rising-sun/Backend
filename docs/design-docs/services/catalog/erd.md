# Catalog DB ERD

<!-- visual: relations -->

기준: `cc91c09b` 공통 모델 위 Product #333의 Catalog V2 구현을 반영했다. SKU의 다중 판매 사본 V3는 통합 전이며 아래 product_sales는 현행 모델이다. 운영 DB 적용이나 실제 Kafka 전달 검증 결과는 아니다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| product | PK id; name; main_image_id/url; seller_id; category; version; sale_enabled; deleted_at | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) · [V2](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql) |
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

V2는 기존 사본의 전체 허용을 true로 보존한다. 새 Product 이벤트는 saleEnabled/deletedAt을 명시하며 구형 생략은 true/null이다. 더 큰 version만 반영하되 삭제 사실은 보존한다. 전체 중지는 기존 공개 노출을 바꾸지 않고 구매 가능 조건에 반영하며 삭제는 공개 조회에서 제외한다. [Product 원본·사본 상세](../../commercecore/product/ERD.md)를 따른다.

## 원본과 매핑

- [V1__catalog_baseline.sql](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql)
- [V2__product_sale_control.sql](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql)

JPA 매핑: [Product.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/product/entity/Product.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
