# #331 Order 구현과 통합 검증

상태: **코드·전체 검증 완료, 사용자 영향 두 항목 확인 대기**. Draft PR #343(base Cart). `a68c31da`에서 전체 하네스1362건과 코드 CI43586694261가 통과했다. 후속 사용자 확정으로 구 API 포함 모든 새 주문은10분이고 이미 저장된 기한만 보존한다. 이 후속 변경의 검증은 검증문서 후속 절을 따른다. 진행환불1건·flag-off보상은 직접 사용자 합의로 확인하지 못해 active/Draft를 유지한다. 배포·전체 정책 완료로 표시하지 않는다.

[이슈 #331](https://github.com/2026-2-rising-sun/Backend/issues/331) · [ADR 0331](../../../adr/0331-order-snapshot-lifecycle.md) · [상세설계](../../../design-docs/commercecore/order/설계.md) · [ERD](../../../design-docs/commercecore/order/ERD.md) · [상태](../../../design-docs/commercecore/order/상태관리도.md) · [워크로그](../../../worklog/2026-11-13.md) · [실제 검증 보고](verification.md)

## 범위와 완료 조건

- 주문서 생성 시 UUID 주문번호·SKU 항목·서버 가격 스냅샷·선점을 같은 DB 트랜잭션으로 저장한다.
- 신규 주문의 원래 10분 기한, 결제/만료 경합, 늦은 성공 보상, 수량별 부분 환불·쿠폰 복원을 멱등 처리한다.
- 기존 PK·물리명·주문번호·금액·Cart 출처·15분 기한과 policy_version=0 활성 구매를 보존한다. 가격·재고 원본은 SKU로 통일한다.
- 코드·실제 DTO/API 계약·MD/HTML·마이그레이션·회귀가 일치하고 실행 결과를 기록한 뒤 독립 Order PR 한 개를 작성한다. dev 병합·배포는 범위 밖이다.

## 사용자 확정과 기술 연결안

U1/U2/U3는 사용자 직접 합의다. 다만 미확정 환불1건·일반 환불off의 보상 예외는 직접 합의로 확인하지 못한 구현 선택이다. [현재 동작·영향·대안](../../../design-docs/commercecore/order/설계.md#8-직접-합의와-구분하는-구현-선택)을 숨기지 않으며 사용자 확인 전 정책 전체 확정/완료로 표시하지 않는다.

| 구분 | 결정 |
|---|---|
| 사용자 | 주문서 생성 요청에서 Pending와 선점, 서버 당시 가격 유지, 10분 만료 실패/해제, 늦은 성공으로 부활하지 않음 |
| 사용자 | 별도 예약 테이블 없이 Order 전이와 SKU available/reserved 변경을 같은 DB 트랜잭션에 적용 |
| 사용자 U1 | 환불액 `min(단가×이번 수량, 항목 실결제액−이전 성공 환불액)`. 4000×3/쿠폰2000은 순차4000/4000/2000, 두 개 일괄8000 |
| 사용자 U2 | 주문당 쿠폰 한 장·구매 항목 하나 적용. 누적 주문 전체 수량 환불 성공 시 한 번 복원, 원래 유효기간 유지 |
| 사용자 U3 | 결제 결과 대기 중 즉시 취소 제한 유지. 원래10분 기한은 유지 |
| 기술 구현 | 기존 payment_group/orders PK·테이블명 확장, 새 /v1/order-sheets와 policy_version=1 병행. 기존 저장 행은 버전0 호환, 신규 구 API는 버전2/10분 |
| 기술 구현 | 회원 구매 가드·주문 잠금으로 주문별 미확정 사용자 환불 한 건만 허용. 성공 수량/금액만 사용하며 실패 새 키 재시도 가능 |
| 보류 범위 | 옵션 JSON 제공·버전 계약, 공개 다중 SKU 대표가/합산재고 정책. 임의 옵션이나 Catalog 저장소를 만들지 않음 |

외부 서비스가 같은 환불 알고리즘을 사용한다는 근거는 없다. [공식 정책 조사 기록](refund-policy-research.md)은 대안 검토로 보존하며 사용자 결정을 바꾸지 않는다.

## 실제 소유 파일과 의존

| 범위 | 실제 파일/원본 |
|---|---|
| 새 주문·수량 환불 | `services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/`의 OrderSheetController/RefundController·DTO·Service/Lifecycle/RefundService·OrderTimeSource |
| 기존 소비자 전환 | OrderCreation/Checkout/PaymentGroupCheckout/Lifecycle/Outcome·OrderService/ExpirationScheduler와 LegacyOrderSkuService. Sales 저장소 직접 의존 제거, 신규 구 API 주문도 SKU 참조·상품 스냅샷 저장 |
| Order 물리 이행 | Core `db/migration/V104__order_sheets_and_closure.sql`, payment_group/orders/refund_request/refund_target_order/order_payment_closure 및 member_coupon 사용 출처 |
| 최소 Payment 연계 | Core OrderClosureDeliveryService/PaymentDeliveryStore/Dispatcher/ResultHandler·RefundOutcome, 독립 Payment OrderClosureService·제어키/호출 시작/환불 실행 필터 |
| 독립 Payment 이행 | `services/payment-service/src/main/resources/db/payment-migration/V4__order_closure_compensation.sql` |
| 계약 최종 통합 | `contracts/api/commercecore-service.yaml`, 최소 `payment-service.yaml`. Product 승인 API는 Product 원본 커밋을 merge해 보존, Cart CRUD 조각 통합 |
| 문서 | 이 폴더·ADR0331·Order 상세 MD/HTML·현재 서비스 문서/생성 HTML·워크로그 |

실제 포트는 ProductTradingService.lockForNewOrder(Collection<Long>) → ProductTradeSnapshot, SkuService.lockForOrder/findBySkuIds/findByProductId 및 reserve/consume/release/restock, CartSelectionPort.lockOwned/lockForCompletion/deletePurchased를 사용한다. DTO를 복제하지 않는다. 최종 Cart 선택 DTO는 itemId/skuId/productId/quantity/version이다.

잠금은 신규 생성에서 회원 가드 → Cart 출처 → Product 전체 ID순 공유 잠금 → SKU 전체 ID순 쓰기 잠금이다. 완료/해제는 주문 상태를 직렬화하고 SKU ID순으로 수량을 처리한다. 환불은 주문/환불 기록 잠금 → 성공 수량·금액 → SKU 복구 → 마지막 전량 쿠폰 복원을 같은 트랜잭션에 적용한다. 외부 HTTP는 잠금 트랜잭션 커밋 뒤 호출한다.

구 productId API는 상품의 SKU가 하나일 때 지원한다. 0개는404, 여러 개는409로 새 주문서 경로를 안내한다. Sales 이력이 없는 신규 SKU도 지원하며 새 구 API 주문은 SKU 참조와 상품 스냅샷을 기록한다. 과거 Sales 참조/FK는 그대로 유지한다. 가격·재고는 SKU에서만 읽는다. Cart 옵션 SKU가 기본 SKU와 다르면 409로 새 주문서 경로를 안내하며 임의 기본 SKU로 주문하지 않는다. 이미 생성된 버전0 주문의 완료/해제/환불에는 현재 판매허용을 요구하지 않는다.

## 계약·실패 복구

[Payment 종료/보상 계약](payment-closure-contract.md)을 구현했다. 종료 의도는 만료/선점 해제와 같은 Core 트랜잭션에 저장한다. Payment는 paymentId+원 paymentRequestId의 영속 제어키로 생성·종료·호출 시작을 직렬화한다. 이미 호출된 UNKNOWN은 실패로 단정하거나 새 승인 호출로 바꾸지 않는다. 늦은 SUCCESS 보상은 일반 환불 flag가 off여도 검증된 종료 보상만 실행·복구한다. 보상 결과는 SKU·Cart·쿠폰 효과를 재적용하지 않는다.

실제 HTTP 검사에서 서비스 인증 경로 누락403과 기본 RestClient의 숫자 Instant 직렬화400을 발견했다. 정확한 SERVICE_commercecore matcher를 추가하고 Core DTO의 Instant에 STRING 직렬화를 지정했다. 수신 Instant/ISO 검증은 유지한다. 일반 사용자 Bearer만으로 이 API를 허용하지 않는다.

## 의존 PR과 저장 상태

순서는 Product #333 → SKU #334 → Cart #332 → Order #331이다. PR base도 dev → Product 브랜치 → SKU 브랜치 → Cart 브랜치 순서다. 검증된 정확 SHA를 일반 merge하여 ancestry를 보존하고 cherry-pick/rebase/force push는 하지 않는다. 공통 문서 각 모듈 기록을 보존한다.

최종 통합은 `ddf4d083`(Order `0f59e17c` + Cart `6f9dda99`)이며 Product/SKU/Cart ancestry를 보존했다. 후속 fixture 정합성과 복구 집계 보완을 포함한 코드 HEAD는 `a68c31da`다. 실제 코드 검증과 사용자 영향 확인 대기를 구분한다.

## 최신 검증과 남은 확인

[최종 검증 보고](verification.md)와 [14일 기록](../../../worklog/2026-11-14.md)을 따른다. 전체 하네스1362건은 실패/오류/스킵0이며 Core169/309, PostgreSQL 전용 업무·이행244건, 외부Kafka2·직렬화29표본을 포함한다. NO-SOURCE와 선택되지 않은 작업은 통과 건수에 포함하지 않았다.

사용자 영향 두 항목이 남아 active를 유지한다. 옵션 JSON 공급·공개 다중 SKU/대표가·가격순은 보류 범위며 외부 PG 제공자·Redis·AWS·운영 배포는 미검증이다. 최종 PR HEAD의 CI·저장 상태와 이 제한을 함께 전달한다.
