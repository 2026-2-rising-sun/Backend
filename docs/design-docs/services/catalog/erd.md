# Catalog DB ERD

<!-- visual: relations -->

기준: Product V2·SKU V3를 반영한 현재 모듈 모델이다. product_sales는 SKU별 사본이며 sales_id가 PK다. 통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다. 운영 DB 적용이나 실제 Kafka 전달 검증 결과는 아니다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| product | PK id; name; main_image_id/url; seller_id; category; version; sale_enabled; deleted_at | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) · [V2](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql) |
| product_sales | PK sales_id; product_id 비유일; price; status; available; version | [V3](../../../../services/catalog-service/src/main/resources/db/migration/V3__sku_sales_replica.sql) |
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

V2는 기존 사본의 전체 허용을 true로 보존한다. 새 Product 이벤트는 saleEnabled/deletedAt을 명시하며 구형 생략은 true/null이다. 더 큰 version만 반영하되 삭제 사실은 보존한다. 현재 코드는 전체 중지 중에도 기존 공개 노출을 유지하고 구매 가능 조건만 false로 만든다. 이 노출 선택은 직접 사용자 답변을 기다리는 기존 기술 동작이며 사용자 확정 정책이 아니다. 삭제는 공개 조회에서 제외한다. [Product 원본·사본 상세](../../commercecore/product/ERD.md)를 따른다.

## 원본과 매핑

- [V1__catalog_baseline.sql](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql)
- [V2__product_sale_control.sql](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql)

JPA 매핑: [Product.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/product/entity/Product.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.

## 단일 SKU 공개 호환

단일 판매정보 API는 SKU를 대표가·합산 재고로 축약하지 않는다. 상세는 상품의 전체 SKU가 둘 이상이면 503이다. 목록은 검색·카테고리·공개 상태에 해당하는 상품 중 전체 SKU가 둘 이상인 상품이 있으면 커서·LIMIT 적용 전 503이다. 비공개 SKU도 전체 수에 포함하므로 상태 필터로 차단을 우회하지 않는다. 조건 밖/삭제 상품은 해당 목록을 막지 않는다. 두 목록 조회는 REPEATABLE_READ의 같은 스냅샷으로 검사·페이지를 계산한다. 이는 기존 단일값 계약의 기술 구현이며 공개 다중 SKU 정책을 새로 채택한 것이 아니다.

[V3 이행](../../../../services/catalog-service/src/main/resources/db/migration/V3__sku_sales_replica.sql) · [목록 HTTP 회귀](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/product/controller/CatalogQueryHttpTest.java) · [PostgreSQL 동일 회귀](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/product/controller/CatalogQueryPostgresTest.java).
