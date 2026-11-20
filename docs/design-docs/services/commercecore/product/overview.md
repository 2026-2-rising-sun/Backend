# Product 모듈

Product #333의 상품 표현 정보·전체 판매 제어·소프트 삭제와 거래 검증 경계를 설명한다. 기준은 `cc91c09b` 위의 이 워크트리 구현이며 배포/운영 DB 적용 결과는 아니다. SKU #334의 가격·재고·개별 상태 구현과 후속 Order 연결은 별도로 통합한다.

- [ERD 원본](erd.md) · [ERD HTML](erd.html)
- [상태 원본](overview.md) · [상태 HTML](overview.html)
- [실행 계획](../../../../worklog/plans/active/333-product-module/README.md) · [API 변경 조각](../../../../worklog/plans/active/333-product-module/api-changes.md)
- [정책과 대안](../../../../adr/0333-product-sku-boundary.md) · [현재 서비스 ERD](../erd.md)

MD가 원본이고 HTML은 저장소 렌더러로 생성한다. 실제 테스트 결과와 환경·미실행 범위는 [작업 기록](../../../../worklog/2026-11-13.md)을 따른다.

## Product 상태 — 전체 제어와 SKU 개별 조건

<!-- visual: relations -->

기준: `cc91c09b` 위 Product #333 구현. ProductLifecycleService와 ProductTradingService, Catalog의 최소 제어 반영을 설명한다. SKU/Order의 후속 연결은 통합 대상이며 운영 배포 완료를 뜻하지 않는다.

[시각 자료](overview.html)

전체 스위치는 매장의 상품 전체에 붙은 판매 허용 조건이다. 각 SKU의 개별 중지나 재고 부족과 독립적이다. 전체 스위치를 다시 켜도 개별 중지된 옵션을 재개하거나 재고를 늘리지 않는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 미생성·기존 JSON 등록 | 판매 준비·전체 false | 상품 기본정보 저장. SKU는 별도 생성이며 SKU 초기 READY. Product 등록과 Outbox version 0 저장. |
| 미생성·통합 등록 | 전체 true | 이미지·상품·모든 SKU를 한 번에 저장. SKU 최초 ON_SALE·예약 재고 0. |
| 판매 준비·전체 false | 전체 true | 판매자가 판매 시작, 본인·미삭제·version 확인. Product만 변경하고 Outbox 기록. |
| 전체 true | 전체 false | 판매자 전체 중지. Product만 변경하고 SKU 상태·수량은 보존. |
| 전체 false | 전체 true | 판매자 재개. 기존 개별 SKU 중지/품절 조건을 그대로 유지. |
| 전체 true | 삭제·전체 false | 본인·version 확인 후 deleted_at 저장. 원본·이미지·SKU·기존 주문 보존. |
| 전체 false | 삭제·전체 false | 판매 준비 또는 중지 중에도 소프트 삭제 가능. |
| 삭제·전체 false | 삭제·전체 false | 같은 판매자의 완료된 삭제 재요청은 추가 이벤트 없이 204. 복구 기능은 추가하지 않음. |

### 불변조건

| 조건 | Product/SKU 동작 | 거래·표시 결과 |
|---|---|---|
| Product false, SKU 판매중·재고 있음 | SKU 값 유지 | 신규 거래 포트 거절. Catalog의 기존 공개 노출은 유지하되 구매 불가, SALE_SUSPENDED. |
| Product true, SKU 개별 중지 | Product true 유지 | SKU 조건 때문에 구매 불가. 전체 재개가 개별 중지를 해제하지 않음. |
| Product true, SKU 재고 0 | Product true 유지 | SKU 품절 조건으로 구매 불가. Product에 SOLD_OUT을 저장하지 않음. |
| Product 삭제 | deleted_at != null이면 sale_enabled=false | 신규 수정·거래·SKU 생성 거절. 일반 조회와 공개 조회 제외. |
| 전체 상태가 요청과 동일 | version 일치 검사 후 변경 없음 | 중복 Outbox/버전 증가 없음. 오래된 version은 409. |
| 타 판매자/소유자 없는 레거시의 새 제어 요청 | 새 전체 제어/삭제 권한 부여 안 함 | 403. 기존 기본정보 수정의 레거시 처리와 구분. |

### 잠금과 기존 주문

신규 주문은 호출자 트랜잭션에서 모든 Product ID를 정렬해 공유 잠금·미삭제/전체 허용을 검사한 다음 모든 SKU를 잠근다. 반환 값은 불변 ProductTradeSnapshot이다. Product 포트는 MANDATORY여서 단독 트랜잭션으로 먼저 커밋하지 않는다.

주문이 공유 잠금을 먼저 얻으면 해당 트랜잭션 뒤에 전체 중지/삭제가 완료된다. 중지/삭제가 먼저 완료되면 후속 신규 거래는 최신 상태로 거절한다. 앞선 일반 조회가 같은 영속성 컨텍스트에 있어도 잠금 뒤 다시 읽는다.

SKU 생성은 미삭제·본인만 검사하므로 전체 중지 중에도 준비할 수 있다. 기존 주문의 완료·예약 해제·환불 성공 복구에는 신규 거래의 판매허용 검사를 재사용하지 않는다. 해당 수량/멱등성 처리는 Order/SKU가 소유한다.

### API와 근거

| API | 요청/응답 | 실제 상태 |
|---|---|---|
| PATCH /v1/seller/products/{productId}/sale-state | saleEnabled·version 필수, 200 Product envelope | 구현·Order 검토 승인한 YAML 제한 반영. 루트 BearerAuth와 SELLER 권한. |
| DELETE /v1/seller/products/{productId} | version 필수 쿼리, 204 | 구현. 본인 삭제 재요청 멱등, 첫 삭제 version 충돌 409. |

[Lifecycle 구현](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/service/ProductLifecycleService.java) · [거래 포트](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/service/ProductTradingService.java) · [API 조각](../../../../worklog/plans/active/333-product-module/api-changes.md) · [ERD](erd.md) · [작업 기록](../../../../worklog/2026-11-13.md)

버전 충돌이나 Outbox 기록 실패는 부분 반영하지 않는다. 정책/대안은 ADR을 원본으로 유지하며 HTML에 미확정 전이나 별도 정책을 추가하지 않는다. 실제 PostgreSQL 공유 잠금 보장은 해당 실행 기록이 있을 때만 검증됐다고 판단한다.

## 이미지·상품·SKU 통합 등록 (#372)

`POST /v1/seller/products/registrations`는 대표 이미지 `file`과 상품·SKU JSON `request`를 받는다. 기존 JSON 기본정보 등록·이미지 업로드·단일 판매 API는 유지한다. 통합 등록은 Product를 최초부터 saleEnabled=true, SKU를 ON_SALE·입력 가용 재고·reserved=0으로 만든다. 중간 판매 준비 전이는 없다. 재고 0은 SOLD_OUT 표시다.

상품·이미지 메타데이터·모든 SKU·Product/SKU Outbox·최초 응답은 한 DB 트랜잭션이다. Product와 SKU 내부 포트를 동기 호출하고 SKU 생성을 이벤트로 요청하지 않는다. SKU는 1~100개이며, 다중 SKU는 공백 제거 후 서로 다른 optionLabel(1~255자)로 구분한다. 단일 SKU의 표시명은 생략할 수 있다. 구조화된 옵션 조합·자동 생성은 이번 범위가 아니다.

파일을 쓰기 전에 product_registration에 판매자+요청 키, SHA-256 요청 지문, 서버 발급 파일 키를 별도 커밋한다. 그 행을 잠근 뒤 파일 저장과 업무 DB 트랜잭션을 수행한다. 파일 I/O 동안 DB 연결·행 잠금을 유지하는 비용을 감수해 같은 키의 활성 등록·재시도·정리를 직렬화한다. SHA-256 지문은 정규화한 요청 JSON(배열 순서 포함), 표시 파일명, 이미지 바이트를 포함한다. 같은 내용은 저장된 최초 응답을 201로 재응답하고 다른 내용은 409다. 키는 판매자별이며 완료 기록은 자동 만료시키지 않는다. 기존 Product 등록 키와 별도 이름 공간이다.

파일은 DB와 원자적으로 커밋되지 않는다. 롤백 확인 뒤 전용 파일을 즉시 지우며, 커밋 결과가 불명확하면 완료 기록 재조회 전에는 지우지 않는다. DB 접속·파일 삭제 실패는 복구 기록을 보존한다. 스케줄러는 1시간 이상 된 미완료 요청 최대 50개를 1분마다 FOR UPDATE SKIP LOCKED로 확인하고, product_image 메타데이터가 없는 키만 삭제한다. 연결·공유 이미지와 완료 요청은 보존한다. 로컬 저장소는 그 키로 추적되는 중단 임시 파일도 회수한다. 같은 미완료 요청을 재시도하면 남은 파일을 재사용하거나 같은 키로 다시 저장한다.

initialSkuCount는 통합 등록의 불변 초기 SKU 수다. 이후 Product snapshot에도 유지한다. Catalog가 상품 이벤트와 첫 SKU만 수신한 순간에도 다중 SKU를 단일 상품으로 오인하지 않게 한다. 기존 Catalog 단일 판매 계약은 다중 SKU를 503으로 거절한다. 다중 SKU 고객 선택·대표 가격·합산 재고는 제공하지 않으며, Core 주문의 최종 검증은 유지한다.

### 적용 순서

Catalog V4와 소비자·조회 보호를 먼저 모든 인스턴스에 적용한다. 이어 Core V108과 새 생산자를 모든 인스턴스에 적용한 뒤 통합 등록 API 트래픽을 연다. initialSkuCount를 모르는 구형 Catalog가 실행 중인 채로 통합 등록을 열면 부분 수신 보호가 보장되지 않는다. 실제 배포는 이번 로컬 구현 검증에 포함하지 않는다.

## Catalog CDC 상품 스냅샷

`ProductInfoEventPublisher`는 상품 저장과 같은 TX에서 `commerce.catalog.snapshots.v1`의 ProductSnapshotChanged를 저장한다. key/aggregateId는 product:{id}, metadataVersion은 Product의 JPA version(최초0)이다. 이미지 ID/URL·설명·카테고리·판매자·판매 제어·삭제 이력·initialSkuCount를 모두 담는다. timestamp는 flush 후 DB 저장 정밀도로 읽어 재적재 같은 버전과 원문을 일치시킨다. 구 commerce.product-info.v1에 이중 발행하지 않는다. 삭제 Product는 재적재에서도 남고 복원 기능을 추가하지 않는다.

## 통합 등록 직후의 CDC·알림 분포

상품1개와 SKU N개를 등록하면 같은 업무 TX의 Outbox는 상품 CDC1건·SKU 메타데이터 CDC N건·기존 SalesRegistered N건이다. SKU2개이면 총5건이며 타입·토픽·키가 서로 달라 중복 메타데이터 발행이 아니다.

| 타입 | 토픽 | 키 | 등록 직후 건수 |
|---|---|---|---|
| ProductSnapshotChanged | commerce.catalog.snapshots.v1 | product:{productId} |1 |
| SkuSnapshotChanged | commerce.catalog.snapshots.v1 | sku:{skuId} | SKU별1 |
| SalesRegistered | commerce.sales-info.v1 | SKU ID 숫자 문자열 | SKU별1 |
| InventorySnapshotChanged | commerce.catalog.snapshots.v1 | sku:{skuId} |0. 영속 dirty revision을 기본60초 배치가 합쳐 발행 |

일반/claim relay는 Catalog CDC 행을 배제하고 기존 판매 알림만 전송한다. Product/SKU CDC 또는 기존 판매 알림 Outbox 저장이 실패하면 이미지 메타데이터·상품·모든 SKU·완료 멱등 응답·Outbox는 함께 롤백한다. 별도 TX의 미완료 파일 복구 위치는 보존하고 참조 없는 파일을 정리하며 같은 요청으로 재시도할 수 있다.

실제 PostgreSQL CHECK 장애 주입과 일반/claim relay의 EmbeddedKafka 판매 알림 수신 범위는 [상품 등록 회귀 후속 기록](../../../../worklog/2026-11-20-product-bundle-cdc-regressions.md)을 따른다. 이 relay 검사는 CDC 메시지 수동 재발행이나 실제 앱→CDC→Catalog 종단 증거가 아니다.
