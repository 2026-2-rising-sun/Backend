# Catalog 사본 동기화 서비스 간 흐름

<!-- visual: relations -->

기준: 2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`([#379 병합](https://github.com/2026-2-rising-sun/Backend/pull/379)). Connect 구성은 [Infra #58](https://github.com/2026-2-rising-sun/Infra/pull/58)의 main `cd6778590bcb57eb0603506f2130912e23bd37cc`와 함께 읽는다. 병합은 운영 배포를 뜻하지 않는다.

[시각 자료](catalog-sync.html) · [최종 로컬 검증과 한계](../../worklog/2026-11-20-cdc-refund-status.md)

## 책임과 전달 경로

Core가 상품·가격·재고 원본을 소유하고 Catalog가 탐색용 조회 사본을 소유한다. 상품명이나 가격 변경은 업무 트랜잭션에서 즉시 Outbox에 기록한다. 예약·소진·해제 등 재고 변경은 SKU의 영속 revision을 올리고, 배치가 여러 변경을 최신 수량 한 건으로 합친다. DB 커밋, Kafka 전달, Catalog 반영은 각각 다른 단계다.

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 상품·SKU 메타데이터 변경 | Core Outbox | 업무 변경과 같은 TX에 ProductSnapshotChanged·SkuSnapshotChanged 전체 봉투를 저장한다. |
| 재고 변경 | dirty SKU | inventory_version을 증가시킨다. 수량 변경마다 Catalog Outbox를 쓰지 않는다. |
| dirty SKU | Core Outbox | 기본 60초 fixedDelay 배치가 최신 InventorySnapshotChanged와 완료 revision을 같은 TX에 저장한다. |
| Core Outbox | Connect | public.event_outbox 중 op=c/r이고 topic=commerce.catalog.snapshots.v1인 행만 먼저 필터한다. |
| Connect | Kafka | Filter 다음 EventRouter가 topic·key·전체 봉투 TEXT를 전달한다. 관리 UPDATE/DELETE는 이벤트가 아니다. |
| Kafka | Catalog 사본 | 독립 버전·원문 중복 검사와 삭제 fence를 적용하고 receipt·조회 사본을 같은 TX에 저장한다. |
| Catalog 표시 | Core 주문 검증 | 표시 재고로 구매를 확정하지 않는다. Core가 현재 가격·재고·판매 조건을 검증한다. |

## 기본 60초 재고 배치의 의미

[InventorySnapshotBatch](../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/event/InventorySnapshotBatch.java)는 `inventory_version > inventory_published_version`인 SKU만 ID순으로 최대 500건 선점한다. `FOR UPDATE SKIP LOCKED`로 여러 인스턴스가 대상을 나누고, Outbox와 완료 표시는 함께 커밋·롤백한다. 처리 중 새 변경은 다음 dirty revision으로 남는다.

60초는 이전 배치가 끝난 뒤 다음 실행까지의 기본 지연이다. 모든 재고의 매분 기록이나 장애·적체를 포함한 최대 전파 SLA가 아니다. 신규 SKU도 최초 재고가 dirty이므로 메타데이터보다 재고 도착이 늦을 수 있다. 필요한 기존 판매 상태 알림은 유지하지만 순수 재고 변경의 기존 InventoryChanged 알림은 발행하지 않는다.

## 초기 적재와 전달 경로 전환

신규 토픽·Catalog 수신 호환과 DB migration을 준비하고, Core DB의 logical WAL·권한·publication을 설정한다. Core의 일반/claim relay가 Catalog 토픽을 제외하는 것을 확인한 뒤 connector를 등록해 RUNNING과 실제 전달을 대조한다. 현재 Core 설정은 이미 이 제외를 적용한다. Catalog는 `catalog.legacy-events.enabled=false`로 구 상품/판매 리스너와 새 경로의 혼합을 막는다.

기존 원본과 사본을 채울 때는 `commercecore.catalog.backfill.enabled=true`인 명시적 Core 기동을 사용한다. [CatalogSnapshotBackfill](../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/event/CatalogSnapshotBackfill.java)은 상품 공유잠금→SKU ID순 잠금 아래 상품·SKU·재고 전체 스냅샷과 삭제 이력을 기존 revision 그대로 Outbox에 넣는다. 중단 후 재실행할 수 있다. Connect의 initial snapshot은 남아 있는 Outbox를 읽는 단계이며, 이 업무 초기 적재를 대신하지 않는다. 과거 HTTP 판매 backfill의 version 0보다 새 독립 버전 경로가 우선한다.

relay 제외와 connector가 준비된 뒤 명시적 초기 적재를 실행하고 버전·누락·지연을 대조한다. 이미 새 Core가 기록한 Outbox도 connector의 initial snapshot/streaming으로 전달한다. connector 중단 시 relay 자동 fallback은 없다. CDC 행은 `published_at`을 갱신하지 않으므로 NULL을 실패로 세거나 age TTL로 삭제하지 않는다. 되돌리기는 connector 중지·offset 확인·생산 경계 기록과 중복 대조가 필요하며, 제외 설정만 해제하면 이미 전송한 행도 재발행될 수 있다. [Infra 등록·복구 절차](https://github.com/2026-2-rising-sun/Infra/blob/cd6778590bcb57eb0603506f2130912e23bd37cc/local/cdc/README.md)를 따른다.

## Catalog 적용과 조회

- Product metadataVersion, SKU metadataVersion, SKU inventoryVersion은 독립적이다. 한 영역의 높은 버전이 다른 영역의 갱신을 막지 않는다.
- 낮은 버전은 무시하고 같은 버전·같은 payload는 무변경이다. 같은 버전의 다른 payload와 같은 eventId의 다른 봉투는 충돌로 거절한다.
- SKU 메타데이터·재고가 먼저 오면 영속 보관하고 둘 다 모여야 판매 사본을 만든다. 상품이 없으면 공개 JOIN에서 숨긴다. Product/SKU 삭제 fence는 높은 생존 버전이나 재고 도착으로 부활시키지 않는다.
- available·salesStatus는 지연된 표시다. PurchaseCheck는 사본 재고 0/수량 초과만으로 SOLD_OUT/EXCEEDS_STOCK을 확정하지 않는다. purchasable/orderable은 Core 검증으로 들어갈 수 있다는 뜻이다. maxQuantity 999999999는 입력 형식 상한이다.
- 다중 SKU의 기존 단일값 API는 대표 가격·합산 재고를 만들지 않고 503을 유지한다. Frontend의 stock/status 버튼 차단은 [후속 인계](../../worklog/2026-11-19-catalog-cdc-frontend-handoff.md)이며 이번 Backend 문서 변경으로 해결되지 않는다.

## 상세와 근거

[Catalog 적용·조회](../services/catalog/overview.md) · [Core SKU·재고](../services/commercecore/sku/overview.md) · [상품 통합 등록](../services/commercecore/product/overview.md) · [공통 Outbox](../services/common-module/outbox.md) · [이벤트 계약](../../../contracts/events/commerce-catalog-snapshots.yaml)
