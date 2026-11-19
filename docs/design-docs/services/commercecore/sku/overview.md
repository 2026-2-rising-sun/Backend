# SKU 모듈 구현과 목표 설계

통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다.

상태: 기존 단일 SKU 판매 API·상품 조회를 SKU 원본으로 전환했다. 최종 감사 후속의 모듈별 검사와 PR 상태는 [2026-11-15 기록](../../../../worklog/2026-11-15-sku.md)을 따른다. Product `729d2d5f`를 일반 merge했다. 공개 다중 SKU 생성·조회/옵션 선택·대표가·가격 정렬은 보류다. 운영 배포 결과와 구분한다.

- [ERD 원본](erd.md) · [ERD HTML](erd.html)
- [판매 상태·수량 원본](overview.md) · [상태 HTML](overview.html)
- [결정과 이유](../../../../adr/0334-sku-price-inventory.md)
- [실행 계획](../../../../worklog/plans/active/334-sku-module/README.md)
- [현재 CommerceCore 전체 모델](../erd.md) · [서비스 문서](../overview.md)

이 폴더는 #334 목표와 구현 진행 상태를 설명한다. 현재 서비스 문서는 새 판매 API와 전환 전 Order 호환 경로를 구분한다. 두 위치에서 정책을 따로 결정하지 않고 ADR의 확정 정책과 실제 코드에 맞춰 갱신한다. HTML은 같은 폴더의 MD에서 생성하며 외부 서버·CDN 없이 읽는다.

## 내부 포트

[SkuService](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuService.java)는 `findBySkuIds`·`findByProductId`·`findByProductIds`, `lockForOrder`, `create`, `changePrice`·`changeSaleStatus`·`adjustAvailable`, `reserve`·`consume`·`release`·`restock`을 제공한다.

조회는 예약하지 않는다. 거래 잠금과 모든 변경은 호출자의 활성 쓰기 트랜잭션을 요구한다. Product 검증·Order 전이/환불 적용 보호·SKU 수량·Outbox를 같은 트랜잭션으로 묶는다. Product 전체 판매허용은 Product 소유이며 새로운 거래 전에 확인한다. 기존 주문 소진·해제·환불 복구에는 신규 판매허용을 다시 요구하지 않는다.

## API와 이행 상태

기존 `/v1/sales` REST 경로는 SkuSalesService를 통해 SKU·Outbox만 쓴다. Product 본인·미삭제 검증과 잠금 뒤 가격·수량·상태를 변경하며 전체 중지 중에도 준비할 수 있다. 단일 상품 등록은 Product 행 배타 잠금으로 중복 생성을 직렬화한다. 조회·거래 내부 포트는 SKU 원본을 사용한다. 기존 SalesService/RegistrationService와 Sales 테이블은 선행 브랜치의 Order 호환·이력 경로로 남아 있으며 Order #331이 최종 거래 연결을 통합한다. 이 중간 브랜치를 독립 배포하지 않는다. Core API 최종 편집자는 Order이고 SKU 담당이 실제 변경 조각을 제공한다. Catalog API 판매/SKU 구간은 SKU가 맡는다.

V102는 기존 Sales ID·가격·재고·버전을 SKU로 복사하는 확장 단계다. 소비자 연결 전에 이 중간 상태를 독립 배포하거나 두 원본에 병행 쓰지 않는다. V102/V3 번호와 Product→SKU→Cart→Order 순서는 코디네이터 기술 조정이며 사용자 개별 번호 정책이 아니다. 대표가·최저가·합산 재고·새 가격 정렬·SKU별 방송 노출은 미결정이다.


## 일반 버전 제거와 동시성 제어

SKU의 일반 `version`은 수정 요청과 비교하지 않는 값이므로 V107에서 제거한다. `SkuView`와 판매 등록·가격 변경·상태 변경 응답에도 이 필드를 반환하지 않는다. Product의 `@Version`과 상품 수정 요청의 버전, Cart·기존 Sales의 버전은 유지한다.

가격·상태·수량 변경은 `SkuEventTracker.watch()`의 SKU 행 잠금과 필드별 조건부 UPDATE로 보호한다. 신규 주문의 Product→SKU 잠금 순서와 활성 쓰기 트랜잭션 요구는 유지한다. 이벤트 최신성 판별용 `snapshotVersion`은 별개이며, 최종 표시 가격·available·상태 변경 때 증가한다. Catalog의 `product_sales.version`은 이 이벤트 버전을 저장하므로 제거하지 않는다.

판매 응답의 필드 제거는 외부 계약 변경이다. 조사한 Frontend `e2748d7`은 판매 변경 응답을 사용하지 않고 상품을 다시 조회한다. Catalog·Live의 판매 일괄 조회 DTO에도 일반 SKU 버전은 없다. 저장소 밖 소비자와 실제 배포본은 별도 확인 대상이다. 구 애플리케이션은 `sku.version`을 읽고 쓰므로 컬럼 삭제 시 구 인스턴스를 함께 실행하지 않는다. 이번 변경은 배포 실행을 포함하지 않는다.

## 단일값 호환과 미완료 흐름

상품 페이지를 먼저 자른 뒤 productIds를 모아 SKU를 한 번에 읽는다. 상태 필터는 Product 페이지 계산 전에 EXISTS로 적용해 SKU 수가 상품 total/offset을 늘리지 않는다. 기존 단일 SKU는 최신 가격·available·품절 파생 표시를 반환한다. 복수 SKU를 기존 단일 응답으로 읽으면 Core409/Catalog503이며 첫 SKU·최저가·합산재고를 임의 선택하지 않는다.

판매자 내부 listSkus는 본인·미삭제 상품의0..N개를 제공한다. 판매자의 다중 SKU 생성/조회 HTTP·고객 옵션 선택·상품별 공개 skus[]·Live 다중 SKU 노출은 아직 구현되지 않았다. 옵션 JSON 저장/중복/전파 상세와 대표 가격 정책은 사용자 후속 결정 사항이다. [실제 API 조각](../../../../worklog/plans/active/334-sku-module/api-changes.md)과 [검증 결과](../../../../worklog/plans/active/334-sku-module/README.md)를 함께 본다.

## SKU 판매 상태와 수량 변화

통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다.

<!-- visual: relations -->

상태: #334의 SkuService/SkuView/SkuEventTracker 및 기존 Sales HTTP→SkuSalesService 전환을 반영한다. Product `729d2d5f` 통합 기준이다. 기존 외부 enum을 보존하고 SOLD_OUT만 파생한다. 공개 다중 SKU 흐름과 Order/Cart 최종 통합은 별도다.

[시각 자료](overview.html)

### 전이

| 출발 | 도착 | 조건·수량 효과 |
|---|---|---|
| 신규 SKU | READY | price>0, 초기 available>=0, reserved=0. Product 존재·소유·미삭제 확인과 같은 트랜잭션. |
| 신규·통합 등록 | ON_SALE | 상품·모든 SKU와 같은 트랜잭션. available 입력·reserved=0, 재고 0은 SOLD_OUT 표시. |
| READY | ON_SALE | 판매자 판매 시작. available=0이면 저장 의도는 ON_SALE, 표시 상태는 SOLD_OUT. Product 전체 허용도 신규 구매 때 필요. |
| READY | PRIVATE | 판매자 개별 중지. READY로 수동 복귀하는 전이는 없음. |
| ON_SALE | PRIVATE | 판매자 개별 중지. Product 전체 스위치로 개별 상태를 덮어쓰지 않음. |
| PRIVATE | ON_SALE | 판매자 재개. 재고가 0이면 SOLD_OUT으로 표시. |
| 표시 ON_SALE | 표시 SOLD_OUT | 저장 의도 ON_SALE인 SKU의 available이 0이 됨. 별도 SOLD_OUT 저장 전이가 아님. |
| 표시 SOLD_OUT | 표시 ON_SALE | 저장 의도 ON_SALE인 SKU의 available이 양수가 됨. PRIVATE/READY는 자동 판매하지 않음. |

### 거래 수량

| 동작 | available | reserved | 보호·실패 |
|---|---|---|---|
| Order 생성 예약 | q 감소 | q 증가 | q>0, ON_SALE, available>=q. Order 생성과 같은 TX. |
| 결제 성공 소진 | 유지 | q 감소 | Order 결과 첫 적용, reserved>=q. |
| 결제 전 취소·실패·만료 해제 | q 증가 | q 감소 | Order 전이 첫 적용, reserved>=q. |
| 판매 후 환불 성공 복구 | 환불 대상 q 증가 | 유지 | Order 환불 건/항목 적용 보호. 이미 해제한 만료 주문의 보상 환불 제외. |
| 판매 가능 재고 증감 | delta 증감 | 유지 | 음수 available·available+reserved의 INT_MAX 초과 거절. 예약분을 판매자 증감으로 변경하지 않음. |
| 현재가 변경 | 유지 | 유지 | KRW 1원 이상. Order 단가 스냅샷은 유지. |

### 트랜잭션·실패 복구

Product들을 ID순으로 공유 잠금·검증한 다음 SKU들을 ID순 FOR UPDATE로 잠근다. SKU 변경은 MANDATORY로 호출자 트랜잭션에 참여하고 독립 커밋하지 않는다. 수량 조건 실패·Outbox 실패·후속 Order 실패 시 전체 롤백한다. 예약 상태 엔티티나 Cart 상태를 새로 만들지 않는다.

수량 조건만으로 주문 A/B의 예약을 구별하지 못한다. Order가 취소/환불 적용 여부를 먼저 보호한 뒤 SKU를 한 번 변경해야 한다. 이미 생성된 주문의 consume/release/restock에는 Product/SKU의 신규 판매허용 조건을 재사용하지 않는다.

현재가·available·외부 표시 상태의 최종 스냅샷이 같으면 이벤트를 만들지 않는다. 변경하면 SKU별 snapshotVersion을 올리고 같은 트랜잭션의 Outbox에 기존 sales-info 토픽/키/필드로 기록한다. 실제 Kafka 전달 검증은 별도다.

### 근거

- [SkuService](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuService.java)
- [SkuView 표시 상태](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/dto/SkuView.java)
- [SkuEventTracker](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/event/SkuEventTracker.java)
- [H2·Outbox Mock 업무 테스트](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/sku/service/SkuServiceTest.java)
- [기존 Sales 상태와의 이행 구분](overview.md)
- [설계 이유·확정 사항](../../../../adr/0334-sku-price-inventory.md)

### 정수 범위와 남은 제한

판매자 증감과 restock은 available+reserved가 INT_MAX를 넘지 않게 같은 UPDATE에서 검사한다. 예약은 합계를 유지하고 소진은 합계를 줄이며 해제는 합계를 유지하므로, 유효한 행에서 판매자 보충으로 기존 예약의 해제를 막는 상태를 만들지 않는다. 조건 실패는 수량·버전·Outbox와 호출자 작업 전체를 롤백한다.

소진한 과거 판매수량은 두 재고 열에 남지 않는다. 판매자가 이후 INT_MAX까지 보충한 뒤 과거 주문을 환불하면 restock 공간 부족으로 실패할 수 있다. 이번 수정은 별도 판매잔량 원장·BIGINT 재고 이행·환불 상한 정책을 추가하지 않으며 이 기존 한계를 숨기지 않는다. 이전 구현에서 이미 available+reserved가 INT_MAX를 넘은 행의 자동 보정도 하지 않는다. 재고0 판매시작 성공은 기존 기술 채택이며 직접 사용자 답변 대기다.

## CommerceCore 판매 상태

<!-- visual: relations -->

기준: #334의 기존 단일 판매 API→SKU 전환과 Product `729d2d5f` 통합. [SKU 모듈 상세](overview.md)와 같은 정책이다. 실제 검증은 실행 계획·워크로그에서 환경과 건수를 구분한다.

[시각 자료](overview.html)

### 범위와 불변조건

SKU는 판매자의 의도 READY·ON_SALE·PRIVATE를 저장한다. READY는 생성 전용이며 돌아갈 수 없다. SOLD_OUT은 ON_SALE이고 available=0일 때만 외부에 표시하는 호환 enum이다. 현재 기술 동작은 초기 재고0에서도 판매 시작이 가능하다. 이 성공/거절 정책은 직접 사용자 답변 대기 중이며 이번 수정에서는 바꾸지 않는다. Product 전체 허용·미삭제와 SKU 상태·available을 함께 확인해 신규 주문을 판단한다. Product 전체 중지/재개는 SKU 상태나 수량을 덮어쓰지 않는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규·기존 분리 등록 | READY | 본인·미삭제 Product 검증 뒤 SKU 생성. available 입력·reserved=0. |
| 신규·통합 등록 | ON_SALE | 상품·모든 SKU와 같은 트랜잭션. available 입력·reserved=0, 재고 0은 SOLD_OUT 표시. |
| READY | ON_SALE | 판매자 시작. available=0이면 외부 SOLD_OUT 표시. |
| READY | PRIVATE | 판매자 비공개 요청. |
| ON_SALE | PRIVATE | 판매자 비공개. 예약과 기존 주문 상태는 변경하지 않는다. |
| PRIVATE | ON_SALE | 재공개 요청. available=0이면 외부 SOLD_OUT 표시. |
| ON_SALE 표시 | SOLD_OUT 표시 | 선점/조정으로 available=0. 저장 sale_status는 ON_SALE 유지. |
| SOLD_OUT 표시 | ON_SALE 표시 | 해제·복구·보충으로 available>0. PRIVATE/READY는 자동 공개하지 않는다. |

### 수량과 실패

가격·수량·판매 의도는 SKU 행 잠금과 필드별 조건부 UPDATE로 변경한다. Order가 Product 정렬 공유 잠금 후 SKU 정렬 잠금·단가 스냅샷·선점을 같은 트랜잭션으로 묶는다. 소진·해제·환불 복구에는 Product 신규 판매 조건을 다시 검사하지 않는다. Order 전이/환불 적용 기록이 중복 수량 변경을 막으며 SKU는 주문별 멱등성 원장이 아니다.

표시 가격·available·외부 상태가 바뀐 커밋만 sales-info Outbox와 snapshot_version을 만든다. reserved만 소진하면 표시 이벤트가 없다. Outbox 저장 실패는 SKU 변경과 함께 롤백한다.

### 구현과 검증 근거

- [SkuSalesService](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuSalesService.java)
- [SkuService](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/service/SkuService.java)
- [SKU 상태 상세](overview.md)
- [실행 계획·결과](../../../../worklog/plans/active/334-sku-module/README.md)

기존 SalesService/RegistrationService·테이블은 이 브랜치의 전환 전 Order 호환·이력용이다. 판매 HTTP와 Product 판매 조회는 이 경로를 사용하지 않는다. 최종 Order/Cart 통합·실제 브로커 검증은 후행 PR에서 확인하며 이 중간 브랜치를 독립 배포하지 않는다.

통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다. 예약 해제 공간과 환불 복구 상한은 [SKU 수량 상세](overview.md#정수-범위와-남은-제한)를 따른다.

## 통합 등록의 최초 판매 상태 (#372)

통합 등록 서비스는 활성 DB 트랜잭션에서 createOnSale을 호출한다. SKU를 처음부터 ON_SALE로 생성하고 reserved=0, available=입력 초기 재고, snapshot_version=1을 저장한다. registered 추적기가 최종 스냅샷을 같은 커밋의 Outbox에 남긴다. 기존 create와 단일 판매 API의 READY 생성은 그대로 유지한다. optionLabel은 등록 항목을 구분하는 선택적 표시명이며 구조화된 옵션 정의는 아니다. 다중 SKU에서는 비어 있지 않은 고유 표시명이 필수다.
