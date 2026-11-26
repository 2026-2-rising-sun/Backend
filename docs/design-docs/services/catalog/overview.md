# Catalog

책임: 상품·판매·쿠폰 공개 조회 사본.

상품·판매·쿠폰 원본의 변경 주체는 CommerceCore다. 사본의 버전과 원본의 확정을 구분한다.

기준: 2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`의 #379 병합 상태. Catalog V5의 독립 버전 사본과 단일 SKU 공개 호환 모델을 설명한다. [상위 CDC 흐름](../../flows/catalog-sync.md)과 [최종 로컬 검증·미실행 범위](../../../worklog/2026-11-20-cdc-refund-status.md)를 함께 본다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/catalog-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [판매 사본·표시 상태](overview.md) · [시각 자료](overview.html)

## 원본·갱신

[구현](../../../../services/catalog-service/src/main/java) · [마이그레이션](../../../../services/catalog-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.

현재 CDC·독립 버전·조회 변경과 환경별 검사는 [B 실행 기록](../../../worklog/2026-11-19-catalog-cdc-sync.md)을 따른다. 이전 SKU 검증은 [2026-11-15 기록](../../../worklog/2026-11-15-sku.md)에 보존한다.

## Catalog 판매 사본·표시 상태

<!-- visual: relations -->

현재 사본 적용은 아래 독립 revision 경로를 따른다. 이전 Product V2·SKU V3 단일 사본 감사는 [과거 기록](../../../worklog/2026-11-15-sku.md)에 보존한다.

[시각 자료](overview.html)

### 범위와 불변조건

이는 Core 판매 상태를 변경하는 상태 기계가 아니라 사본 갱신과 화면 표시 규칙이다. 원본 재고를 Catalog가 예약하지 않는다. replica/HTTP 조회 설정과 구매 시점 원본 검증을 구분한다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 행 없음 | 독립 revision 사본 | 새 초기 적재는 Core Outbox의 상품·SKU·재고 전체 봉투를 사용한다. SKU·재고 선도착은 영속 대기한다. |
| 행 없음 | 독립 revision 사본 | commerce.catalog.snapshots.v1의 eventType별 handler가 상품/SKU metadataVersion과 inventoryVersion을 따로 비교한다. |
| 사본 revision N | 사본 revision M | M>N만 반영한다. 같은 revision·같은 원문은 무변경, 다른 원문은 충돌이다. eventId 원문도 대조한다. |
| 조회 성공 | 원본 상태 표시 | READY·ON_SALE·SOLD_OUT·PRIVATE를 그대로 매핑한다. 미삭제 상품의 단일 SKU 공개 노출은 ON_SALE/SOLD_OUT이다. purchasable은 saleEnabled=true인 공개 판매 상품의 Core 확인 진입 표시다. 지연 재고로 구매를 승인하거나 수량을 거절하지 않는다. 전체 중지는 purchase-check의 SALE_SUSPENDED로 구분한다. |
| 조회 결과 없음 | NOT_REGISTERED | 정상 조회에서 판매정보가 없는 경우. |
| 조회 실패 | UNKNOWN | 외부 조회 실패를 품절이나 미등록으로 치환하지 않는다. |

### 구현과 검증 근거

- [ProductSalesRepository.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepository.java)
- [ProductSalesBackfill.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/service/ProductSalesBackfill.java)
- [SalesDisplayStatus.java](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/client/commercecore/SalesDisplayStatus.java)

관련 기존 테스트: [ProductSalesRepositoryTest.java](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepositoryTest.java) · [ProductSalesBackfillTest.java](../../../../services/catalog-service/src/integrationTest/java/com/shoppinglive/catalog/sales/service/ProductSalesBackfillTest.java).

현재 전체 중지 중 공개 유지와 재고0 판매 시작 성공은 직접 사용자 답변 대기 중인 기술 동작이다. 이번 수정에서 바꾸거나 사용자 승인으로 재분류하지 않았다. 다중 SKU는 상세에서 503이고, 공개 목록에서는 판매 중 최저가와 재고 합으로 한 줄이 된다. 규칙은 [ERD의 단일값 경계](erd.md#단일-sku-공개-호환)를 따른다. 실제 실행 여부와 Frontend 후속은 [최신 검증 기록](../../../worklog/2026-11-20-cdc-refund-status.md)에서 확인한다.

## 통합 등록의 부분 수신 보호 (#372)

Product snapshot의 선택적 initialSkuCount는 통합 등록 당시 SKU 수다. 기존 이벤트의 null/생략은 유지하고 새 이벤트는 1~100 정수만 허용한다. 상품 replica가 이를 보존하므로 최초 상품 이벤트보다 높은 버전이 먼저 도착해도 같다. 상세는 initialSkuCount>1인 상품을 SKU 사본이 1개만 도착해도 503으로 거절한다. 공개 목록은 도착한 SKU로 대표값을 계산한다. 상품 이벤트가 없으면 상품을 공개하지 않으며, 단일 SKU 등록은 상품과 판매 사본이 모두 있을 때 기존 구매 조건을 적용한다. 새 경로는 SKU 메타데이터와 재고를 독립 revision으로 비교한다.

이 보호는 옵션 구매 지원이 아니다. 상세는 기존 단일 판매 계약의 실패 동작을 유지한다. 공개 목록의 대표 가격과 합산 재고는 표시용이며 주문 가격과 수량은 Core가 확정한다.

## Catalog 통합 스냅샷 수신

`CatalogSnapshotListener`는 `commerce.catalog.snapshots.v1`만 공통 수신한다. 봉투의 source/schemaVersion/eventType/UUID/키와 payload ID·타입·필수 필드를 검사한다. `catalog_snapshot_state`의 aggregate 행을 잠가 세 종류를 직렬 적용하고 `catalog_snapshot_receipt`와 조회 모델을 같은 트랜잭션에 저장한다. DB 장애는 기존 무제한 재시도 경로로 넘기고, 잘못된 원문은 정상 성공으로 표시하지 않는다.

- ProductSnapshotChanged: 상품 metadataVersion으로 상품명·설명·이미지·판매자·전체 제어를 반영한다. 최초0도 유효하다.
- SkuSnapshotChanged: SKU metadataVersion으로 가격·옵션·판매의도를 반영한다. 수량은 변경하지 않는다.
- InventorySnapshotChanged: inventoryVersion으로 수량만 바꾼다. 가격·판매의도는 보존한다. ON_SALE+available=0이면 SOLD_OUT으로 표시한다.

SKU/재고는 상품보다 먼저 도착해도 보관한다. 두 SKU 영역이 모두 있어야 product_sales를 만들며 상품이 없으면 공개 JOIN에서 숨는다. Product/SKU 삭제는 영속 fence로 유지하고 같은 ID의 높은 생존 버전으로 복원하지 않는다. initialSkuCount>1의 단일값 API503 보호를 유지한다. 쿠폰 소비·기존 동기화는 범위를 늘리지 않는다.

main 설정은 구 상품·판매 리스너의 `catalog.legacy-events.enabled=false`로 단일 생산·소비 경로를 선택한다. 구 코드는 과거 적체 대조용으로 남기며 신규 CDC와 동시에 켜서 원문을 혼합하지 않는다. 새 초기 적재는 Core의 명시적 `commercecore.catalog.backfill.enabled=true` 기동으로 수행한다. 무버전 HTTP backfill을 새 이벤트보다 우선하지 않는다.

PurchaseCheck는 공개 상품의 사본 재고0/수량 초과를 SOLD_OUT/EXCEEDS_STOCK으로 확정하지 않는다. maxQuantity는 입력 형식 상한999999999이며 실제 재고는 Core 주문 생성에서 검증한다. available과 salesStatus는 지연된 표시 정보로 보존한다. Frontend 읽기 점검에서는 `ProductDetailPage.tsx`가 status와 stock>0으로 직접 버튼을 막는 것을 확인했으며, Frontend 수정은 이번 소유 범위 밖이다. 서버 변경이 그 화면까지 해결했다는 의미가 아니다.
