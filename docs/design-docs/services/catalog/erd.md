# Catalog DB ERD

<!-- visual: relations -->

기준: Product V2·SKU V3를 반영한 현재 모듈 모델이다. product_sales는 SKU별 사본이며 sales_id가 PK다. 통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다. 운영 DB 적용이나 실제 Kafka 전달 검증 결과는 아니다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| product | PK id; name; main_image_id/url; seller_id; category; version; sale_enabled; deleted_at; initial_sku_count | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) · [V2](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql) |
| product_sales | PK sales_id; product_id 비유일; price; status; available; version | [V3](../../../../services/catalog-service/src/main/resources/db/migration/V3__sku_sales_replica.sql) |
| catalog_snapshot_state | PK aggregate_id; metadata_version/inventory_version(-1=미수신); 각 payload TEXT; deleted fence | [V5](../../../../services/catalog-service/src/main/resources/db/migration/V5__catalog_snapshot_state.sql) |
| catalog_snapshot_receipt | PK event_id; 원문 봉투 TEXT. 조회 모델과 동일 TX | [V5](../../../../services/catalog-service/src/main/resources/db/migration/V5__catalog_snapshot_state.sql) |
| coupon_display | PK coupon_id; fixed_discount; sold_out; version; 유효 기간 | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) |
| coupon_display_product | PK (coupon_id,product_id) | [V1](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql) |
| index_task | PK id(증가); product_id 비유일; requested_at. 사본과 같은 TX로 기록 | [V6](../../../../services/catalog-service/src/main/resources/db/migration/V6__search_index_task.sql) |
| shedlock | PK name; lock_until; locked_at; locked_by. 검색 색인 잠금 | [V6](../../../../services/catalog-service/src/main/resources/db/migration/V6__search_index_task.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| coupon_display_product.coupon_id | coupon_display.coupon_id | 물리 FK · ON DELETE CASCADE |
| product_sales.product_id | product.id | 논리 참조 · 이벤트 도착 순서 차이, FK 없음 |
| coupon_display_product.product_id | product.id | 논리 참조 · FK 없음 |
| product.id | Core.product.id | 서비스 간 원본 ID · FK 없음 |

## 제약과 이관

조회 사본은 이벤트 도착 순서가 다를 수 있어 product와 product_sales 사이 FK가 없다. 상품 version은 0 이상, 판매 backfill은 version 0, 쿠폰 display version은 1 이상이다.

V2는 기존 사본의 전체 허용을 true로 보존한다. 새 Product 이벤트는 saleEnabled/deletedAt을 명시하며 구형 생략은 true/null이다. 더 큰 version만 반영하되 삭제 사실은 보존한다. 현재 코드는 전체 중지 중에도 기존 공개 노출을 유지하고 구매 가능 조건만 false로 만든다. 이 노출 선택은 직접 사용자 답변을 기다리는 기존 기술 동작이며 사용자 확정 정책이 아니다. 삭제는 공개 조회에서 제외한다. [Product 원본·사본 상세](../commercecore/product/erd.md)를 따른다.

## 원본과 매핑

- [V1__catalog_baseline.sql](../../../../services/catalog-service/src/main/resources/db/migration/V1__catalog_baseline.sql)
- [V2__product_sale_control.sql](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql)

JPA 매핑: [Product.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/product/entity/Product.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.

## 단일 SKU 공개 호환

단일 판매정보 API는 SKU를 대표가·합산 재고로 축약하지 않는다. 상세는 상품의 전체 SKU가 둘 이상이면 503이다. 목록은 검색·카테고리·공개 상태에 해당하는 상품 중 전체 SKU가 둘 이상인 상품이 있으면 커서·LIMIT 적용 전 503이다. 비공개 SKU도 전체 수에 포함하므로 상태 필터로 차단을 우회하지 않는다. 조건 밖/삭제 상품은 해당 목록을 막지 않는다. 두 목록 조회는 REPEATABLE_READ의 같은 스냅샷으로 검사·페이지를 계산한다. 이는 기존 단일값 계약의 기술 구현이며 공개 다중 SKU 정책을 새로 채택한 것이 아니다.

[V3 이행](../../../../services/catalog-service/src/main/resources/db/migration/V3__sku_sales_replica.sql) · [목록 HTTP 회귀](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/product/controller/CatalogQueryHttpTest.java) · [PostgreSQL 동일 회귀](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/product/controller/CatalogQueryPostgresTest.java).

## 통합 등록 SKU 수 (#372)

V4는 product.initial_sku_count nullable INTEGER(1~100)를 추가한다. Core가 발행한 통합 등록 초기 SKU 수이며 단일 SKU로 잘못 노출되는 것을 막는 정보다. 기존 상품과 구형 이벤트는 null이다. 최신 snapshot을 반영해도 이미 아는 초기 수를 null로 덮지 않는다.

## 독립 revision 저장

Catalog V5의 상태 테이블은 상품/SKU별 row lock 단위다. payload는 초기0 revision도 수용하며 미수신은-1로 구분한다. SKU 두 영역 중 하나만 도착하면 product_sales를 확정하지 않는다. product_sales.version은 새 경로에서 metadataVersion의 표시 사본이고 재고 최신성은 catalog_snapshot_state.inventory_version이 소유한다. eventId 수신 원문과 같은 revision의 payload를 각각 비교한다. receipt/상태/공개 사본 갱신은 한 TX이며 오류면 함께 롤백한다. 삭제 fence는 정리하지 않는다. 이벤트 전환 전의 legacy 행은 제거하지 않지만 새 current snapshot 적재와 구 offset 대조가 필요하다.

## V5 저장 경계 회귀

`catalog_snapshot_state`는 조회 사본에 적용한 독립 revision·payload·삭제 fence를, `catalog_snapshot_receipt`는 eventId별 수신 봉투 원문을 보관한다. 두 테이블은 사본 반영과 재처리를 위한 Catalog 내부 상태이며 Core 상품 원본의 변경 권한이나 상품 등록 멱등키를 소유하지 않는다. 이미지도 Core의 ID/URL 참조만 보관하며 이미지 파일·파일 저장 메타데이터 테이블을 만들지 않는다.

`CatalogSchemaPostgresTest`는 위 두 테이블을 포함한 업무 테이블6개와 Flyway 이력 테이블1개의 **정확한 목록**을 검사한다. `product.idempotency_key` 부재, Core에서 받은 상품 ID 사용, 최초 버전0 허용·음수 거절 검사도 유지한다. V5 추가 테이블을 누락한 기대 목록을 고쳤으며, 조건을 부분 포함 검사로 완화하지 않는다. 실제 실패와 수정 후 검증은 [Catalog migration 후속 기록](../../../worklog/2026-11-20-catalog-migration-expectations.md)을 따른다.

## 검색 색인 작업 (#419)

스냅샷 반영 TX가 검색 문서 값이 바뀐 상품을 `index_task`에 한 줄씩 추가한다. 상품 스냅샷, SKU의 가격·판매 상태 변경, 삭제, 품절↔재입고가 대상이며 재고 수량만 바뀐 경우와 무시한 옛·중복 이벤트는 기록하지 않는다. 같은 상품의 동시 기록이 키 충돌로 사본 반영을 실패시키지 않도록 product_id를 PK로 두지 않는다.

부분 색인(`catalog.search.enabled=true`일 때만)은 상품별로 모아 Catalog DB에서 문서를 다시 만들고 ES alias `products`에 bulk로 반영한다. 삭제·공개 SKU 없음은 문서 삭제다. 대표 가격은 판매 중 SKU 최저가, 모두 품절이면 품절 SKU 최저가다. 재고 수량과 쿠폰은 문서에 넣지 않는다. 처리 후 읽은 시점의 마지막 task id까지만 지워, 색인 중 새로 쌓인 작업과 ES가 실패한 상품은 다음 주기에 다시 처리한다. `shedlock`의 `catalog-search-index` 잠금을 DB 시각으로 잡아 Pod가 여러 개여도 한 번만 돈다. 전체 색인(#420)도 같은 잠금 이름을 쓴다.

