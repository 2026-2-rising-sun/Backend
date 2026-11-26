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

상세와 구매 사전확인은 SKU를 대표가나 합산 재고로 축약하지 않는다. 상세는 상품의 전체 SKU가 둘 이상이면 503이다.

공개 목록(#421)은 SKU가 여러 개인 상품을 한 줄로 반환한다. 판매 중 SKU가 하나라도 있으면 ON_SALE이고 가격은 판매 중 SKU 최저가, 재고는 판매 중 SKU 재고의 합이다. 모두 품절이면 SOLD_OUT이고 품절 SKU의 최저가와 재고 합이다. 비공개 SKU는 계산에서 뺀다. 가격 정렬과 가격 기준은 검색 문서의 같은 대표 가격이다. 통합 등록에서 SKU가 일부만 도착한 상품도 도착한 SKU로 계산한다.

목록은 ES 검색 인덱스에서 조건에 맞는 상품 id를 정렬된 순서로 찾고, 가격, 재고, 판매 상태는 응답 때 위 규칙으로 DB에서 다시 계산한다. 색인이 늦어 응답 시점에 삭제됐거나 공개 SKU가 없는 상품은 뺀다. ES에 연결할 수 없거나 alias가 아직 없으면 DB로 대신 검색하지 않고 503 SERVICE_UNAVAILABLE이다. 다음 페이지 커서는 ES 정렬 값(search_after)이며 이전 형식 커서는 400이다.

[V3 이행](../../../../services/catalog-service/src/main/resources/db/migration/V3__sku_sales_replica.sql) · [목록 HTTP 회귀(ES)](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/product/controller/CatalogQueryHttpTest.java) · [PostgreSQL 동일 회귀(ES)](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/product/controller/CatalogQueryPostgresTest.java).

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

alias가 없으면 부분 색인은 ES에 쓰지 않고 작업을 남긴다. alias 없이 쓰면 ES가 `products`라는 일반 인덱스를 자동으로 만들어 이후 alias 교체를 막기 때문이다. 첫 인덱스는 전체 색인이 만든다.

## 전체 색인 (#420)

전체 색인은 `catalog.search.full.check-interval`(기본 1분)마다 할 일이 있는지 본다. alias가 없거나(첫 배포), 지금 인덱스를 만든 뒤 `catalog.search.full.cron`(기본 매일 04:00, `Asia/Seoul`)이 지났으면 다시 만든다. 정해진 시각에 한 번만 실행하지 않는 이유는, 그 순간 부분 색인이 잠금을 쥐고 있어도 다음 확인 때 실행되게 하려는 것이다. 잠금을 잡은 뒤 한 번 더 확인하므로 Pod가 여러 개여도 한 번만 만든다.

1. `products_<UTC 시각, 밀리초까지>` 이름의 빈 인덱스를 alias 없이 만든다. 매핑은 부분 색인과 같은 `elasticsearch/products.json`이다.
2. 삭제되지 않은 상품을 id 순서로 `batch-size`(기본 500)개씩 읽어 부분 색인과 같은 기준으로 문서를 만들고 새 인덱스에 넣는다. 공개 SKU가 없는 상품은 넣지 않는다.
3. 새 인덱스를 refresh하고 문서 수가 넣은 수와 같으면 alias `products`를 한 번의 요청으로 새 인덱스로 옮긴다.
4. 직전 인덱스 하나는 되돌리기용으로 남기고 그보다 오래된 `products_*` 인덱스는 지운다.

중간에 문서 반영이나 DB 조회가 실패하거나 문서 수가 다르면 새 인덱스를 지우고 alias는 옮기지 않는다. 검색은 기존 인덱스로 계속되고, 다음 확인 때 다시 시도한다. 전체 색인 중에는 같은 잠금 때문에 부분 색인이 돌지 않는다. 그 사이 바뀐 상품은 `index_task`에 남아 alias를 옮긴 뒤 새 인덱스에 반영된다. `lock-at-most-for`(기본 30분)는 전체 색인 한 번보다 길어야 한다. 짧으면 도중에 잠금이 풀려 부분 색인이 옛 인덱스에 쓰고, 그 변경이 새 인덱스에서 빠질 수 있다.

### alias 되돌리기

새 인덱스에 문제가 있으면 alias를 직전 인덱스로 옮긴다. 인덱스 이름은 먼저 조회한다.

```bash
curl -s "$ES/_cat/aliases/products?v"         # 지금 alias가 가리키는 인덱스
curl -s "$ES/_cat/indices/products_*?v&s=index" # 남아 있는 인덱스. 시각이 이름에 있다
curl -s -X POST "$ES/_aliases" -H 'Content-Type: application/json' -d '{
  "actions": [
    {"remove": {"index": "<지금 인덱스>", "alias": "products"}},
    {"add":    {"index": "<직전 인덱스>", "alias": "products"}}
  ]
}'
```

되돌린 인덱스를 만든 뒤 cron 시각이 이미 지났다면 다음 확인 때 전체 색인이 다시 돈다. 원인을 고치기 전까지 다시 만들지 않으려면 `CATALOG_SEARCH_ENABLED=false`로 색인을 끈다. 꺼도 `index_task`는 쌓이므로 다시 켜면 밀린 상품부터 반영된다. 되돌린 뒤 전체 색인이 돌면 문제 있던 인덱스는 오래된 인덱스로 지워진다.

