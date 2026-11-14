# #334 SKU 현재 가격·재고 통합 구현 계획

상태: 단일 판매 API·Product 조회의 SKU 전환 검증 중 · 공개 다중 SKU 흐름 보류

2026-11-13 후속 구현 Task `task_65aabcea44d3`/`ctx_af90941ad5c7`를 시작했다. 사용자의 코드·MD/HTML 구현 지시가 아래 과거 착수 대기를 대체한다. Core V102·Catalog V3와 적용 순서는 코디네이터 기술 조정으로 배정됐다. 같은 세션 Sol Medium 적용은 코디네이터가 저장·재조회했다. [모듈 상세·ERD·상태 HTML](../../../design-docs/commercecore/sku/README.md)을 실제 코드와 함께 갱신한다.

현재 SkuService/SkuRepository/SkuView/SkuEventTracker·V102, Catalog SKU별 사본/V3 위에 Product729d2d5f를 일반 merge했다. Sales HTTP 등록·변경·조회와 Product 판매 조회는 SkuSalesService를 통해 SKU 원본으로 전환했으며 API 의미·오류 조각을 Order 최종 편집자에게 전달했다. 기존 Order/Cart 연계는 후행 PR에서 통합하므로 이 중간 상태를 독립 배포하지 않는다. 대표가·가격 정렬·다중 SKU 활성화 미결정은 독립 내부 기반 구현을 막지 않는다.

관련 자료: [이슈 #334](https://github.com/2026-2-rising-sun/Backend/issues/334) · [ADR 0334](../../../adr/0334-sku-price-inventory.md) · [작업 기록](../../../worklog/2026-11-13.md) · [SKU FigJam](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=424-1279)

조사 기준: `refactor/#334-sku-module`, `cc91c09b753a12a352bda65481210e264d3e88b0`. fetch와 ff-only 동기화 후 기준이 같음을 확인했다. 현재는 사용자 후속 지시에 따라 내부 기반을 구현하고 모듈 연계를 준비한다. 미결정 사업 정책은 확정으로 바꾸지 않으며 독립 구현은 계속한다.

구현 전환 시 코디네이터가 이 기존 세션에 gpt-6.1-sol/medium을 명시 적용·재조회한다. 작업자는 모델이나 전역 설정을 스스로 바꾸지 않는다. 구현 단계 FigJam은 코디네이터가 맡고, SKU 담당은 코드·테스트·로컬 설계/HTML·PR과 FigJam 변경 근거 전달을 맡는다.

## 목표와 범위

SKU 한 행에 현재 가격과 `available`·`reserved`를 두고 생성·조회·가격 변경·재고 증감·예약·소진·해제·환불 복구를 SKU 모듈에서 수행한다. Order가 주문/환불의 처리 여부를 보호하고 같은 DB 트랜잭션에서 SKU 연산을 호출한다. 별도 Price·Inventory 모듈과 재고 예약 테이블은 만들지 않는다.

금액은 KRW 1원 단위의 양의 정수다. 주문서 생성 때 서버 현재가를 주문에 저장하고 기존 주문 단가는 유지한다. 향후 환불·배분의 반올림·버림·잔여 원 처리는 별도 재검토 대상으로 유지한다. 가격 이력·예약 가격·새 할인/쿠폰·실물 재고 오차 보정 업무와 독립 Payment 개편은 추가하지 않는다.

확정 정책과 대안의 이유는 ADR을 원본으로 삼는다. 옵션 JSON·Catalog 관리의 세부 설계는 Product의 [후속 이슈](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=448-1280)를 따르며 이 계획에서 고정 옵션 테이블이나 JSON 저장 위치를 새로 정하지 않는다.

## 다른 담당과 대조한 확정 사항

- Product: 옵션 없는 상품도 기본 SKU를 둔다. 상품 기본정보를 먼저 저장할 수 있다. SKU 생성에는 가격·초기 재고가 필수이며 초기 재고 0을 허용한다. Product 대표 이미지 관계를 포함해 DB FK 미사용을 선택했다. 새 Product–SKU는 논리 참조로 설계하고 기존 다른 모듈 FK 이행은 각 담당과 조율한다.
- Product 후속 확정: Product 전체 판매허용 스위치와 SKU 개별 판매 상태를 분리한다. 전체 중지/재개로 SKU 상태를 덮어쓰지 않고 Product에 품절을 중복 저장하지 않는다.
- 신규 판매 준비 후 시작 확정: 신규 Product는 sale_enabled=false, 신규 SKU는 READY로 생성하고 판매자가 판매 시작을 선택한다. 기존 데이터의 판매 상태는 보존한다. READY는 최초 생성 상태이며 판매 후 READY로 수동 복귀하는 전이를 추가하지 않는다.
- Cart: 담기만으로 예약하지 않는다. 상품별로 묶어 표시하고 SKU별로 조작하며 결제 성공 후 선택 항목을 정리한다. Cart의 주문 연결·편집 제한은 아직 제안이다.
- Order: 주문서 생성 시 Pending·재고 선점·서버 당시 가격/상품 스냅샷을 함께 처리한다. Pending 10분 만료 뒤 실패·예약 해제하며 늦은 성공으로 부활하지 않는다. 이미 해제한 재고를 늦은 성공이나 보상 환불로 다시 복구하지 않는다. 현재 코드의 15분·PAYMENT_CONFIRMING 만료 제외를 목표 정책으로 복사하지 않는다.
- Order: 부분 환불을 지원하고 성공 때 해당 취소를 확정해 재고를 복구한다. 부분 환불 건/항목의 중복 적용 방지는 Order 소유이며 별도 재고 예약 테이블을 추가하는 근거가 아니다.

## 통합 검토에서 고정한 기술 경계

2026-11-13 코디네이터의 통합 경계·판매 준비 확정과 후속 구현 착수 지시를 반영했다. 원본은 `/tmp/commercecore-module-handoff-20261113/`에 있다. 사용자 업무 정책과 코디네이터의 기술 조정을 구분한다.

- Product·Cart 항목·SKU 식별자는 BIGINT/Java Long을 유지한다. 기존 Sales ID를 기본 SKU ID로 보존한다. 충돌·매핑 불가가 실제로 발견될 때만 근거를 붙여 조정한다. 주문 UUID 방향을 다른 식별자 교체로 확대하지 않는다.
- SKU #334가 sales-info 생산자/계약과 Catalog 다중 SKU 판매 사본의 최소 호환 변경을 맡는다. Product #333은 Product 제어/삭제 이벤트와 Catalog Product 사본을 맡는다. 대표가·최저가·합산 재고는 추가하지 않는다.
- 후속 조정: Catalog API의 판매/SKU 구간은 SKU가 최종 편집하고 Product가 변경 조각을 제공한다. 호환안 A의 병행 방향을 통합 검토 기준으로 삼는다. Live는 기존 조회 어댑터의 계약 호환 조사까지만 SKU 범위에 포함하며 방송 업무/API 확대는 별도 결정한다.
- `contracts/api/commercecore-service.yaml`의 최종 편집자는 Order #331이다. SKU는 요청/응답·오류·경로 변경안을 제공하고 공통 YAML을 동시에 편집하지 않는다. Core RefundIntake/Query/Outcome도 Order가 조정한다.
- 기존 적용 마이그레이션은 수정하지 않는다. 코디네이터가 Core V101 Product/V102 SKU/V103 Cart/V104 Order, Catalog V2 Product/V3 SKU를 기술 배정했다. Product→SKU→Cart→Order 순서로 검증된 선행 커밋을 통합하며 임의 cherry-pick·다른 워크트리 미커밋 복사는 하지 않는다.

## 구현 전에 닫을 연계 항목

이미 확정된 정책을 다시 묻지 않는다. 아래 항목은 아직 합의되지 않은 호출·저장·이행 세부 사항이다.

| 항목 | 합의할 내용과 권장 방향 |
|---|---|
| SKU 생성·식별 | Long ID·초기 READY는 확정됐다. 생성 키·중복 정의·Product 검증 포트·생성 호출 시점을 정한다. 가격·초기 재고 필수와 상품 선저장은 유지한다. |
| 전체 판매 제어와 거래 경합 | 주문은 Product가 미삭제·전체 판매허용이고 SKU 개별 조건·재고가 충족될 때만 생성한다. Product를 먼저 공유 잠금으로 확인한 뒤 SKU를 정렬해 잠그는 안을 Product와 검토한다. 기존 주문의 해제·환불 복구에는 신규 판매 허용을 요구하지 않는 안을 권장한다. |
| 주문 재진입·부분 환불 | Order의 생성 요청/환불 건별 식별·적용 여부, 부분 환불 단위와 Cart 연결을 정한다. 만료·늦은 성공 경합과 Payment 통지/보상 계약은 Order와 조율한다. |
| Catalog와 외부 계약 | SKU가 다중 SKU 판매 사본과 Catalog API 판매/SKU 구간 최종 편집을 맡고 병행 호환 A를 검토 기준으로 둔다. 구 단일값 의미를 보존하며 오류 응답·가격 정렬·다중 SKU 활성화 경계는 아직 결정 전이다. Live 범위는 조회 어댑터 호환 조사뿐이다. |
| 공유 파일·데이터 이행 | Sales ID 보존·Core YAML 최종 Order·V102/V3는 정해졌다. 구 API 전환·실제 커밋 통합과 기존 FK 참조 전환을 조율한다. 이번에는 기존 데이터 삭제/초기화를 하지 않는다. |

## 저장 모델과 연산 초안

SKU에는 `id`, `product_id`, `price`, `available`, `reserved`, 동시성 버전·이벤트 스냅샷 버전, 감사 시각이 필요하다. id와 product_id는 BIGINT/Long이며 product_id는 여러 SKU가 공유할 수 있는 논리 참조다. 품절의 저장/응답 표현·옵션 JSON 연결은 연계 결정 뒤 확정한다. 가격/재고 NOT NULL·CHECK와 필요한 유일성은 FK 미사용과 별개로 적용한다.

| 동작 | SKU의 변경 | 호출자 조건 |
|---|---|---|
| 생성 | 유효한 가격·초기 available, reserved=0 함께 저장 | Product 존재·판매자 권한·미삭제 확인과 생성 멱등 처리 계약 |
| 가격 변경 | price만 갱신, 주문 스냅샷 유지 | 양의 정수·범위 검증, 예약과 순서 보장 |
| 판매 가능 재고 증감 | available만 증감, reserved 보존 | 기존 증감 범위에서 음수·정수 오버플로 방지 |
| 예약 | available−q, reserved+q | q>0, 판매 가능, available≥q, Order 생성과 원자 처리 |
| 결제 성공 소진 | reserved−q, available 유지 | Order가 해당 결과를 처음 적용함을 보장 |
| 결제 전 취소·만료·확정 실패 | reserved−q, available+q | Order가 해제 권한과 한 번 적용을 보장 |
| 결제 후 환불 성공 | available+환불 대상 수량 | Order가 환불 건/항목 적용 여부 보호. 만료 후 보상 환불은 제외 |
| Cart·Order 조회 | SKU ID 묶음별 현재 가격·수량·개별 상태 | Cart 조회는 예약하지 않음. 생성 후 주문 금액 원본은 Order |

거래 연산은 호출자의 활성 트랜잭션에 참여하고 독립 커밋하지 않는다. Order 상태/환불 적용 기록, SKU 수량, 해당 Outbox 중 하나라도 실패하면 함께 롤백한다. SKU에는 주문별 예약 상태를 중복 저장하지 않는다.

여러 SKU는 ID 기준의 일관된 순서로 잠근다. Product 잠금이 필요한 경로는 Product들을 먼저 정렬해 잠그고 그다음 SKU를 잠그는 안으로 통일한다. 가격·상태 변경도 같은 SKU 잠금 규칙을 따르고 재고는 조건부 UPDATE로 갱신한다. 과거에 읽은 전체 엔티티 저장으로 최신 재고를 덮어쓰지 않도록 필드별 갱신과 JPA 영속성 컨텍스트·버전 일관성을 검증한다.

현행 snapshotVersion은 외부에 보이는 최종 가격·available·상태 변경을 추적한다. DB 동시성 버전과 혼동하지 않는다. Outbox 원자성·중복/역순 소비 규칙을 보존하며 이벤트 키·필드·토픽 변경을 단순 명칭 정리로 취급하지 않는다.

### 물리 스키마 검토안

다음은 SQL 마이그레이션이 아닌 리뷰용 필드안이다. 테이블명은 `sku`를 제안한다. ID 타입과 신규 READY는 결정됐고 품절 표현·외부 계약은 계속 검토한다.

| 필드 | 제안 타입·제약 | 의미·확정 수준 |
|---|---|---|
| id | BIGINT PK | 기술 경계 확정. 기존 Sales ID 보존, 새 ID 생성 시 보존 범위 이후부터 할당 |
| product_id | BIGINT NOT NULL, FK 없음 | Product 1개에 여러 SKU, 단일 컬럼 UNIQUE 금지 |
| price | BIGINT NOT NULL, CHECK price>0 | KRW 1원 단위 확정, 거래 금액 계산의 곱셈 오버플로는 Order와 검증 |
| available | INTEGER NOT NULL, CHECK available>=0 | 주문 생성 때 선점 가능한 수량 |
| reserved | INTEGER NOT NULL, CHECK reserved>=0, 초기 0 | 아직 소진·해제하지 않은 주문 수량 합계 |
| sale_status | VARCHAR(32) 후보, 초기 READY 확정 | 개별 수동 판매 제어. READY 수동 복귀 금지, 품절 파생 응답은 계약 검토 중 |
| version | BIGINT NOT NULL, 초기 0 후보 | 필드 갱신·영속성 컨텍스트의 동시성 버전 |
| snapshot_version | BIGINT NOT NULL, CHECK >0 | 외부에 보이는 전체 스냅샷의 단조 증가 버전 |
| created_at, updated_at | TIMESTAMP WITH TIME ZONE NOT NULL | 기존 감사 시각 의미 유지 |

`(product_id,id)` 조회 인덱스를 제안한다. 가격·수량의 새 업무상 상한을 추가하지 않고 저장 타입 범위 안에서 덧셈·뺄셈 오버플로를 거절한다. SKU 옵션 조합 유일성은 JSON 저장·정규화 책임이 보류돼 있으므로 임의 문자열 키나 옵션 테이블로 구현하지 않는다. SKU 물리 삭제·복구 필드도 별도 결정 전 추가하지 않는다.

### 논리 ERD의 원본

목표/추가 모델은 [SKU ERD MD](../../../design-docs/commercecore/sku/ERD.md)·[HTML](../../../design-docs/commercecore/sku/ERD.html)을 원본으로 관리한다. Product 1→SKU 0..N, SKU→Order 항목의 논리 참조를 표시하고 FK와 구분했다. 기존 서비스 ERD에도 V102 기반을 추가하되 Sales/Stock와 구 API 전환 전 상태를 구분한다. 목표 HTML의 1280/390 화면 검증은 워크로그에 기록했다.

### 판매·수량 상태의 분리

신규 SKU 초기값은 READY로 확정했다. READY는 최초 준비 상태이며 판매자 수동 변경 대상은 현행처럼 ON_SALE/PRIVATE로 제한하는 안이다. 어떤 상태에서도 READY로 되돌아가는 신규 전이는 추가하지 않는다. 신규 Product의 sale_enabled=false와 별개로 제어하고 기존 상품/SKU 상태를 신규 기본값으로 덮어쓰지 않는다.

후속 구현 지시에서 채택한 기술안은 수동 판매 상태 READY/ON_SALE/PRIVATE와 `available=0` 품절 표시의 분리다. SkuView는 ON_SALE+0일 때만 기존 외부 enum SOLD_OUT을 반환한다. READY/PRIVATE는 재고 0만으로 자동 판매·품절 전환하지 않는다. V102는 기존 SOLD_OUT을 판매 의도 ON_SALE로 이행한다. 신규 SKU 포트에 적용했으며 기존 Sales API는 아직 전환 전이다. 이 결정을 상품 노출·가격 정렬 정책으로 확대하지 않는다.

신규 주문 가능 조건은 `Product 미삭제 ∧ Product 전체 판매허용 ∧ SKU 개별 판매허용 ∧ available≥요청수량`이다. 반면 이미 생성된 주문의 소진·해제·환불 복구는 Order의 거래 상태·한 번 적용 여부로 판단한다. 품절·전체 중지·소프트 삭제 때문에 기존 예약이 영구히 묶이지 않도록 이 경로에서 신규 판매 조건을 재사용하지 않는 안을 권장한다.

수량 상태 효과는 앞 연산 표를 원본으로 삼는다. 결제 성공은 reserved만 줄이고, 결제 전 해제는 available로 이동하며, 실제 판매 뒤 환불은 available을 늘린다. 만료로 이미 해제한 주문의 보상 환불에는 마지막 동작을 다시 호출하지 않는다. 별도 예약 행이 없으므로 예약 상태도 파일은 만들지 않는다.

### 필요한 내부 인터페이스·DTO 초안

내부 SKU 메서드와 SkuView는 Java로 작성했다. 아래 Product 연계와 외부 API는 검토/연결 전이며 내부 포트를 HTTP 계약으로 해석하지 않는다. SKU/Product 식별자의 Java 타입은 Long, 저장 타입은 BIGINT다. 새 식별자 래퍼나 UUID 전환을 추가하지 않는다.

| 소유·소비자 | 필요한 요청/결과 | 트랜잭션·실패 의미 |
|---|---|---|
| Product 제공 → SKU·Order | 상품 ID 묶음의 존재·소유자·미삭제·전체 판매허용 확인 및 정렬 공유 잠금 | 신규 주문에서는 전체 판매허용 필요, SKU 준비에는 존재·소유·미삭제 확인. 범용 불리언 플래그보다 용도가 드러나는 메서드 권장 |
| SKU 제공 → Product | create(Long productId,long price,int initialAvailable,String productName) / SkuView | MANDATORY, 초기 READY·reserved=0. Product 검증 포트와 같은 TX로 연결. 외부 생성 키·중복 정의는 API 전환 때 조율 |
| SKU 제공 → Cart·Product·Order | findBySkuIds, findByProductId / SkuView(skuId,productId,price,available,reserved,개별 상태,version) | 배치 읽기는 선점하지 않음. 누락된 SKU를 호출자가 판별 가능하게 반환. 가격 단위는 KRW 원 |
| SKU 제공 → Order | lockForOrder(정렬 skuIds) / 잠금 후 읽은 SkuView | Product 공유 잠금 뒤 SKU 잠금. 요청에 든 SKU가 Product에 속하는지도 검증 |
| SKU 제공 → Order | void reserve/consume/release/restock(Long skuId,int quantity) | MANDATORY. 영향 행이 1개가 아니면 전체 처리 실패. 멱등성은 Order 전이·환불 적용 기록이 보호 |
| SKU 제공 → 판매자 경로 | changePrice, adjustAvailable, changeIndividualSaleState | 권한 확인·필드별 변경·Outbox 함께 처리. 새로운 expectedVersion 요청 필드는 별도 계약 합의 없이 추가하지 않음 |

수량 부족은 거래 충돌로 반환하고 수량을 일부만 적용하지 않는다. 해제/소진 대상의 reserved 부족은 정상적인 중복 성공으로 숨기지 않고 불변조건 위반으로 실패시킨다. 중복 요청은 그보다 앞선 Order/환불 기록 단계에서 걸러야 한다. DB 교착·일시 실패의 재시도는 전체 트랜잭션 단위로 검토하며 외부 Payment 호출을 DB 잠금 안에 넣지 않는다.

## 책임과 적용 순서안

확정된 작성자와 승인 전인 API 호환·적용 순서안을 구분한다. 다른 워크트리는 수정하지 않는다.

| 대상 | 책임안과 편집 조건 |
|---|---|
| sales에서 이행할 SKU 엔티티·서비스·저장·이벤트와 SKU 스키마 | SKU #334 단일 작성. 기존 호출자 전환 목록과 내부 연산 제공 |
| Product 등록/수정·전체 판매 제어·상위 spec·Product 이벤트/사본 | Product #333이 자기 경로에서 연결. Catalog Product 테이블/소비자 필드도 Product 소유 |
| CartService·Cart 엔티티/저장소/컨트롤러 | Cart #332. 현재 order 패키지 아래 있어도 Cart 소유 |
| 주문 생성·묶음·만료·결제 결과 | Order #331 조정 창구. OrderCreationService·PaymentGroupCheckoutService·PaymentGroupLifecycleService·OrderPaymentOutcomeService의 동시 편집 방지 |
| Core RefundIntake/Query/Outcome의 SKU 호출·부분 환불 적용 | Order #331 조정. SKU는 필요한 수량 연산·실패 의미 제공. 독립 Payment 서비스 개편으로 확대하지 않음 |
| contracts/api/commercecore-service.yaml | Order #331 최종 편집 확정. SKU는 자기 경로/DTO·공통 SKU 식별/오류 변경 조각 제공 |
| commerce-sales-info.yaml·Catalog 판매 사본/소비자 | SKU #334 소유 확정. SKU별 키/버전·백필·조회 최소 호환 변경을 함께 검증 |
| contracts/api/catalog-service.yaml 판매/SKU 구간 | SKU #334 최종 편집, Product #333 변경 조각 제공. 공유 상품 조회 코드의 편집 순서는 별도 조율 |
| Live 기존 조회 어댑터 | SKU #334가 계약 호환만 조사. 방송 업무/API·SKU별 방송 노출·코드 변경을 이번 조사 지시로 확대하지 않음 |
| Flyway·공통 ERD | SKU 소유 V102·Catalog V3. 다른 모듈 참조 이행은 각 담당. 공통 ERD에는 추가 기반과 구 경로의 공존을 표시하고 적용된 기존 마이그레이션은 덮어쓰지 않음 |

권장 순서는 **공통 식별/호출 경계 → SKU 기반·이행 준비 → Product 연결 → Cart·Order 연결 → 소비자 계약 검증 → 기존 Sales 경로 제거**다. 4개 PR의 의존성을 연결하고 기존 호출자가 깨지는 중간 상태를 독립 배포 가능하다고 주장하지 않는다. 실제 병합·배포는 별도 승인 대상이다.

### Sales ID 보존과 Catalog 이행안

V102는 기존 Sales/Stock 한 쌍을 같은 ID의 기본 SKU 한 행으로 옮겨 가격·available·reserved·판매 의도·snapshotVersion을 보존한다. Stock 누락은 제약으로 중단하고 identity를 맞춘다. 기존 값을 신규 READY/0재고로 덮어쓰지 않고 기존 소비자 전환 전 테이블/참조를 제거하지 않는다. 실제 PostgreSQL 이행 검증은 아직 남았다.

Catalog 판매 사본은 SKU ID가 PK가 되고 product_id는 비유일 조회 인덱스가 되는 안을 권장한다. 현재 `product_sales`에서 sales_id를 SKU 식별자로 유지할지 sku_id로 이름을 바꿀지는 계약 조각에서 정한다. 이벤트의 버전 비교도 상품별이 아니라 SKU별로 수행한다. Product 사본의 삭제/전체 스위치는 Product 담당에게서 받고 판매 사본에 Product 상태 원본을 중복 소유하지 않는다.

이벤트 최소 호환안은 기존 sales-info 토픽·숫자 키·salesId를 안정적인 SKU ID의 호환 이름으로 유지하고 snapshotVersion도 그대로 이어가는 것이다. 토픽/필드 개명은 필수로 가정하지 않는다. 단, 기존 소비자는 product_id로 덮어쓰므로 생산자만 다중 SKU 발행으로 바꾸면 안전하지 않다. SKU별 소비·백필 전환과 검증이 먼저 필요하며 배포/PR 순서는 코디네이터가 배정한다. 백필은 기존처럼 없는 SKU에만 version=0으로 넣고 이벤트 최신 값을 덮지 않는 안이다.

### 기존 단일값 API 병행 호환과 남은 결정

실제 소비자를 읽어 다음 제약을 확인했다. 이는 실행 재현 결과가 아닌 정적 코드 근거다.

| 현행 경로 | 다중 SKU에서 그대로 유지할 수 없는 이유 |
|---|---|
| Core SalesController `/v1/sales?productIds=` → Catalog HttpSalesInfoClient | 상품당 SalesInfo 하나의 Map이며 같은 productId 응답 두 개를 예외로 처리한다. |
| Catalog PublicProductItemResponse·PublicProductDetailResponse·purchase-check | price/available/maxQuantity가 단일 숫자이며 구매 사전 확인에도 skuId가 없다. 배열 추가만으로 기존 단일값의 의미가 정해지지 않는다. |
| Catalog CatalogQueryService | product_sales를 상품과 직접 JOIN하고 PRICE_ASC/DESC·커서도 단일 가격 기준이다. PK만 바꾸면 상품 중복 행·페이지 경계 오류가 생긴다. |
| Core SellerProductQueryService | 상품당 단일 SalesLookup을 가정한다. Product 담당이 SKU 목록 응답으로 연결해야 한다. |
| Live HttpSalesClient·BroadcastProductViewService | 같은 productId 두 개를 거절하고 상품 키 Map으로 만든다. 연결에는 salesId가 저장돼 있지만 현재 조회는 그 ID로 선택하지 않는다. |

**통합 검토 기준 A:** 기존 단일 SKU 의미를 보존하는 경로와 새 SKU 계약을 병행한다. 다중 SKU의 임의 대표값은 금지한다. 이 방향을 코디네이터가 채택했으며 아래 세부 경로/응답까지 승인한 것은 아니다.

세부 제안은 SKU ID 배치 조회·상품별 SKU 목록과 Catalog `skus[]` 응답, 선택 skuId의 구매 사전 확인이다. 기존 단일값 API는 SKU가 정확히 하나인 상품에서 같은 값·Sales ID를 유지하고 0개는 기존 미등록 의미를 유지한다. 여러 SKU에 대한 409는 검토 후보였으며 **아직 승인되지 않았다**. 첫 SKU 선택·누락·0원 대체로 모호함을 숨기지 않는다. 구 목록 전체가 다중 SKU 한 건 때문에 실패하는 형태를 피하려면 소비자 계약과 활성화 경계를 함께 정해야 한다. 새 경로/버전·오류 코드는 Order의 Core YAML 통합 및 SKU의 Catalog API 편집에서 맞추고, 가격 정렬·다중 SKU 활성화 경계는 코디네이터의 실제 소비자 확인 후 결정한다.

**비교 대안 B — 현재 검토 기준 아님:** 기존 API 자체를 SKU 배열/skuId 요청으로 일괄 교체한다. 구 호환 경로는 줄지만 Catalog·판매자 조회·Live·Frontend까지 동일 릴리스에서 바꿔야 한다. 현재는 A의 병행 방향으로 검토한다. 상품마다 임의 기본 SKU를 대표가로 고르는 안은 새 대표 정책이므로 두 안에 포함하지 않는다.

가격순 상품 목록에는 별도 결정이 필요하다. 대표가/최저가를 만들지 않는 현재 합의로는 여러 SKU가 있는 상품의 기존 PRICE_ASC/DESC 순서를 정의할 수 없다. 새 계약에서 SKU별 목록으로 가격 정렬할지, 상품별 목록은 가격 정렬 없이 제공하고 기존 단일 SKU 계약만 유지할지는 사용자/코디네이터 선택 사항이다. 기존 기능을 임의 제거하거나 min/max/합산 값으로 대체하지 않는다. SKU 상태 필터·상품 노출의 결합도 Product 정책과 대조한다.

Live는 저장된 salesId를 skuId 조회에 사용할 기술적 근거가 있지만 이것이 신규 방송 연결의 SKU 선택·SKU별 노출 승인은 아니다. SKU 담당의 추가 범위는 기존 조회 어댑터 호환 조사뿐이며 Live/Frontend 코드와 방송 업무/API는 바꾸지 않는다.

### Live 기존 조회 어댑터의 호환 조사

근거 경로는 `services/live-service/src/main/java/com/shoppinglive/live/client/commercecore/`다. 호출자의 실패 영향 확인을 위해 broadcast 서비스도 읽었으나 기능 변경 범위로 포함하지 않았다.

- `SalesClient.get(List<Long>)`는 productIds를 받고 단건 get은 빈 결과를 NOT_FOUND로 구분한다. `HttpSalesClient`는 `/v1/sales`의 raw 배열을 읽고 빈 요청은 호출하지 않으며 한 요청 최대 100개다. 요청하지 않은 productId·같은 productId 두 개·null 응답은 거절한다.
- `SalesSnapshot`은 productId·salesId·price·status·available 필수다. `SalesStatus`는 READY/ON_SALE/SOLD_OUT/PRIVATE이고 알 수 없는 상태는 역직렬화에 실패한다. 기존 단일 SKU의 안정적인 숫자 ID·필드·단위·상태 의미를 보존해야 한다.
- `HttpSalesClient`는 HTTP 오류와 파싱 실패를 UNAVAILABLE로 바꾼다. 방송 연결/조회/시작 서비스는 이를 503으로 노출한다. 따라서 Core에 409를 추가하는 것만으로 Live에서 SKU 선택 필요를 표현할 수 없다. 응답에서 여러 SKU를 빼는 안도 단건에는 NOT_FOUND, 배치에는 목록 누락이나 방송 가능 상품 판단 변경을 만들므로 호환 처리로 채택하지 않는다.
- `BroadcastProductViewService`는 productId Map, `BroadcastStartService`는 productId별 ON_SALE/SOLD_OUT로 방송 가능 여부를 판단한다. `BroadcastProductService`의 신규 연결도 productId로 조회한 하나의 salesId를 저장한다. 저장 salesId만으로 전체 호출 경로가 이미 SKU 선택을 지원한다고 볼 수 없다.
- 기존 `src/integrationTest/java/com/shoppinglive/live/client/ProductClientsTest.java`는 WireMock HTTP로 raw 배열·빈 값/장애 구분·중복/알 수 없는 상태/필드 누락 거절과 X-Service-Token을 확인하는 테스트다. 이번에는 코드를 읽었고 실행하지 않았다. 이행 검증 때 단일 SKU 계약 보존과 합의된 활성화 경계의 회귀 검사를 재사용할 수 있다. 실제 Core–Live 운영 통신 검증과 구분한다.

## 구현 단계

- [x] 두 이슈·채택 이유를 ADR·FigJam에 기록하고 Product·Cart·Order 최신 합의와 대조한다.
- [ ] 1. 위 연계 항목과 필드·호출·실패 응답·공유 파일 책임·수용 기준을 합의한다.
- [ ] 2. Sales·Stock·주문 참조·Cart·Catalog의 이행을 구체화한다. 기존 Sales ID를 기본 SKU ID로 보존해 가격·수량·주문 참조·버전을 유지한다. 신규 기본값은 기존 상태에 일괄 적용하지 않는다. 초기화를 선택하면 로컬 대상과 영향을 먼저 구체화한다.
- [ ] 3. SKU 저장·생성/조회·가격/재고 연산을 구현한다. 조건부 수량 갱신, 잠금 순서, 버전·Outbox를 한 트랜잭션으로 묶는다.
- [ ] 4. 합의한 담당 순서로 Product·Cart·Order를 연결한다. 생성 재전송·취소/만료·성공/실패·부분 환불·늦은 결과를 실제 호출 경로로 검증한다.
- [ ] 5. API·이벤트 소비자와 데이터 이행 회귀를 확인한다. productId 요청에 SKU가 여러 개일 때 임의 SKU를 선택하지 않는다. 구 API·테이블 제거는 호환성 합의에 따른다.
- [ ] 6. 검사 결과와 실제 구현에 맞춰 로컬 문서·기존 ERD/상태 HTML을 갱신하고 #334 PR 한 개를 준비한다. FigJam은 대상 node-id·변경 내용·코드/계약 근거를 코디네이터에게 전달해 반영한다. 커밋·원격 전달은 당시 사용자 허용 범위를 따른다.

## 수용 기준과 검증

아래는 실행할 검증이며 현재 통과 결과가 아니다. 기존 테스트를 재사용하고 의미 있는 실패·동시성 사례를 보강한다.

| 검증 | 기대 결과 | 환경·담당 |
|---|---|---|
| 생성·가격·수량 경계 | 0 초기재고 허용, 0/음수 판매가 거절, 수량 음수·오버플로로 손상 없음 | SKU 단위·통합 |
| 신규 준비 상태·기존 상태 보존 | 신규 Product false/SKU READY, 판매자 시작 전 주문 불가, READY 수동 복귀 금지, 기존 상태 일괄 변경 없음 | Product+SKU·migration |
| 재고 5개에 동시 주문 10개 | 성공 수량≤5, available·reserved 음수 없음 | 실제 PostgreSQL |
| A 2개·B 3개 예약 뒤 A 중복/동시 취소 | A 해제 한 번, B 예약 3개 유지 | Order+SKU 같은 트랜잭션 |
| 가격 변경·예약 경합 | 단가가 거래 순서와 일치, 재고 덮어쓰기 없음 | 실제 PostgreSQL |
| 19,000원 주문 후 21,000원 변경 | 기존 주문/결제 19,000원, 이후 새 주문 21,000원 | Order 연동 |
| 다품목 중 하나 부족·중간 실패 | 주문·모든 예약·Outbox 함께 롤백 | Order+PostgreSQL |
| 취소·만료·결제 결과 중복/경합 | 한 번만 소진/해제, 만료 후 늦은 성공·보상 환불에서 추가 복구 없음 | Order 합의 상태/보상 경로 |
| 서로 다른 부분 환불·같은 결과 재전송 | 합의한 항목/수량만 한 번씩 복구, 실패 시 복구 없음 | Order 환불 기록+SKU |
| 상품 중지·삭제와 진행 중 주문 | 신규 구매 차단, 기존 완료/해제/환불 처리 보존 | Product 연동 |
| 이행·이벤트·소비자 | 데이터/참조 보존 또는 승인 초기화 범위, 여러 SKU 덮어쓰기 없음, Outbox 롤백·중복/역순 소비 보호 | migration·소비자·필요 시 실제 Kafka |
| 단일값 호환·다중 SKU 조회 | 단일 SKU의 기존 ID/가격 유지, 다중 SKU 임의 선택 없음, 상품 중복/커서 손상 없음, Catalog/Live 소비자 계약 일치 | 합의된 API/소비자 회귀 |

현행 SalesStockReserveTest·SalesStockConcurrencyTest의 기본 H2를 PostgreSQL 결과로 보고하지 않는다. 환경 전환형 PaymentGroupIntegrationTest 등도 실제 연결을 확인한다. 테스트 0건·스킵·외부 미실행을 통과로 포함하지 않는다.

구현 후 실행 후보는 [도구 안내](../../../guides/tooling.md)에 따라 변경 범위로 선택한다.

```bash
scripts/check.sh --base origin/dev --dry-run
scripts/check.sh --base origin/dev
./gradlew :services:commercecore-service:test :services:commercecore-service:integrationTest
./gradlew :services:commercecore-service:postgresTest
./gradlew :services:commercecore-service:migrationTest
./gradlew :services:catalog-service:test
scripts/render-api-docs.sh --check
.venv-api/bin/python scripts/render_design_docs.py --check
scripts/check-docs.sh
```

PostgreSQL은 격리 DB와 COMMERCECORE_TEST_POSTGRES_ 환경 설정을 확인한다. Catalog 판매 사본의 테스트·이행 검사도 #334 범위에 포함한다. 외부 Kafka 검사는 브로커 환경을 준비해 따로 실행한다. 계획 작성 단계에서는 서비스 테스트나 외부 환경을 실행하지 않았다.

## 산출물과 현재 상태

- 이유·대안·합의는 ADR, 단계·검증 계획은 이 문서, 실제 결과는 기존 날짜별 워크로그에 남긴다.
- SKU 담당의 마지막 FigJam 편집은 spec 424:1279다. 이후 코디네이터가 같은 spec에 신규 READY 확정을 반영하고 본문을 재조회했다고 보고했다. 새 FigJam 편집은 코디네이터에게 이관했으며 SKU 담당은 같은 영역을 동시에 수정하지 않는다.
- ERD는 기존 [CommerceCore erd.md](../../../design-docs/services/commercecore/erd.md)·erd.html을 갱신한다. 별도 중복 ERD를 만들지 않는다.
- 별도 예약 엔티티 상태도는 만들지 않는다. 기존 [판매 상태](../../../design-docs/services/commercecore/states/sales.md)와 Order/환불 자료에 SKU 조건·수량 효과를 연결하고 새 상태도는 필요할 때만 추가한다.
- API·이벤트는 contracts 원본을 편집하고 HTML을 렌더링한다. 서비스 리소스에 수동 복사하지 않는다.
- 최종 전달은 SKU #334 PR 한 개다. Inventory #335 별도 PR·독립 Payment 개편·승인 없는 병합/배포는 포함하지 않는다.

현재 실제 변경은 Core SKU 내부 기반·V102, Catalog SKU별 사본·V3와 테스트·문서다. FigJam은 코디네이터만 편집한다. 기존 Sales API/거래 호출 전환·실제 PostgreSQL·계약 통합이 남아 있으며 다른 채팅의 기존 결정을 재질문하거나 초기화하지 않는다.


## 2026-11-14 복구 후 구현

시작0b36ba47·clean에서 fetch 및 origin/dev ff-only 확인 후 Product729d2d5f를 merge1b9b4d5b로 통합했다. 충돌은 서비스 ERD/지도·실행 계획·워크로그의 양쪽 내용을 보존하고 생성 HTML을 재생성해 해결했다. 다른 워크트리와 FigJam은 편집하지 않았다.

기존 판매 HTTP는 SkuSalesService의 실제 Product 본인·미삭제 잠금/검증과 SKU·Outbox만 사용한다. 기존 단일 상품 등록은 Product 배타 잠금으로 중복 생성 경합을 직렬화하며 여러 SKU 생성 내부 포트와 구분한다. 가격·수량·상태 변경은 SKU 행을 잠그고 조건부 갱신한다. findByProductIds 배치 포트와 판매자 내부 listSkus를 제공하며, Product 먼저 페이지→일괄SKU 조회 및 페이지 전 상태필터로 N+1/상품 중복 건수를 방지한다.

공개 다중 SKU 생성·판매자 HTTP 목록·고객 옵션 선택·상품별 공개 skus[]·대표가·새 가격 정렬·Live 다중 SKU는 미완/보류다. 기존 단일 응답에 복수 SKU가 있으면 Core409/Catalog503이며 임의 대표값은 없다. 기존 SalesService/RegistrationService와 sales_info/sales_stock은 선행 브랜치의 Order 호환·이력 경로로 남는다. Order #331이 신규 등록의 식별/거래/완료/해제·보상 경로를 SKU에 연결하고 sales_info_id 과거 참조를 보존한다. 이 브랜치만으로 전체 원본 전환·운영 배포 완료를 주장하지 않는다.

[API 의미·오류 조각](api-changes.md), [현행 판매 상태](../../../design-docs/services/commercecore/states/sales.md), [목표 ERD/상태](../../../design-docs/commercecore/sku/README.md)를 현재 코드에 맞췄다. V102/V3 번호와 Product→SKU→Cart→Order 순서는 코디네이터 기술 배정이며 사용자 개별 번호 확정으로 기록하지 않는다.

실제 전용 PostgreSQL16 검증: V102 이행3건·SKU/Product연계 및 동시선점8건·CatalogV3 이행1건 실패/오류/스킵0이었다. 기존Sales ID/값·시퀀스, 누락재고/모순상태 거절, Product 삭제 후 기존 예약 완료/해제/복구, Outbox 실패 롤백, 동시10요청 재고5만 선점을 확인했다. 추가 페이지·전체제어 보존 시나리오를 보강했으므로 최종 재실행 결과는 후속 기록에 남긴다. H2/Mock·실제PG·외부Kafka·CI는 구분한다.


최종 PostgreSQL 재검증은 SKU업무11·V102이행3·CatalogV3이행1·Catalog사본업무9, 총24건 실패/오류/스킵0이었다. 같은 Catalog 저장소 H2 9건도 통과했다. 페이지/전체 제어 보존과 같은 영속성 컨텍스트의 Product 최신 버전 잠금 회귀를 포함한다. 실제 실행의 상세와 실패 수정은 [2026-11-14 워크로그](../../../worklog/2026-11-14-sku.md), Core API 원본 반영은 Order 최종 통합 PR을 따른다. 공개 다중 SKU/후행 통합이 남아 계획은 active로 유지한다.
