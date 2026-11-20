# Order 문서 지도

[현재 상세설계](overview.md) · [독립 ERD](erd.md) · [로컬 상태](overview.md) · [후속 결제 요구](../../../../worklog/plans/active/331-order-audit-fixes/payment-handoff.md)

CommerceCore Order·새 Payment 계약 중계와 기존 legacy 실행 동작을 구분한다. 실제 환불 통합은 팀원 구현을 기다린다. 이전 구현은 보존ref/과거검증으로 남기며 현재 전체기능완료로 표시하지 않는다.


## 2026-11-20 현재 기준과 과거 설명

현재 기준은 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`다. 아래 날짜가 붙은 PR343/2026-11-16 경계는 당시 기록이며 현재 새 주문서의 PREPARE/APPROVAL HTTP 중계와 늦은 승인 환불 요청·결과 소비는 구현돼 있다. 현행 legacy 사용자 환불은 legacy 주문만 접수하고, 새 주문서의 늦은 승인은 구 하이픈 토픽 요청과 payment_relay_refund REQUESTED로 보존한다. 실제 Payment 환불 provider는 없다.

[#378](https://github.com/2026-2-rising-sun/Backend/pull/378)은 미병합 Draft다. 사용자 CANCEL_PENDING, 시스템의 명시적 UNKNOWN/최초 처리 600초 후 COMPENSATION_PENDING, 보상 성공 후 해제·CANCELLED와 공통 dot 토픽은 현재 dev 동작이 아니다. provider 계약 불일치와 paymentId 연결 복구 차단이 남는다. [현행·목표 비교](../../../flows/refund.md)와 [검증 기록](../../../../worklog/2026-11-20-cdc-refund-status.md)을 현재 상태의 기준으로 본다.

## Order 상세설계

과거 PR343의 작업 범위는 **CommerceCore Order와 Commerce 소유 Payment 중계**였다. Payment 소유 코드·DB·API·이벤트·설계 문서는 수정하지 않는다. 기존 금전 연동이 포함된 구현은 보존 ref `archive/order331-before-payment-scope-correction`의 `e08b8aea`에 남는다. 당시 실행 성공은 현재 축소 범위의 실행 성공이나 운영 검증이 아니다.

[ERD](erd.md) · [상태](overview.md) · [ADR0331](../../../../adr/0331-order-snapshot-lifecycle.md) · [기존 실행 기록](../../../../worklog/plans/completed/331-order-module/verification.md) · [현재 보완 계획](../../../../worklog/plans/active/331-order-audit-fixes/README.md) · [CommerceCore 결제 인계 요구](../../../../worklog/plans/active/331-order-audit-fixes/payment-handoff.md)

### 1. 현재 제공 범위

한 주문번호에 여러 SKU 항목의 수량·가격·상품명·판매자·Cart 출처를 보존한다. Product 판매중지·삭제는 신규 주문만 차단하며 선점한 유효 주문 결제는 허용한다. 기존 공개 legacy API와 새 `/v1/order-sheets` 중계를 함께 제공한다. 새 API는 팀원 생성·조회·승인 wire를 사용하지만 실제 환불 구현이 아직 없어 전체 통합은 미완이다.

### 2. 생성과 금액

새 API는 UUID 구매자, 직접 SKU 또는 Cart ID/version, 예상 실결제액, 선택 SKU 쿠폰 한 장을 받는다. 회원 guard→Cart 출처→Product ID 공유잠금→SKU ID 쓰기잠금 순으로 생성·선점·쿠폰 예약을 같은 TX에 저장한다. 동일 키/원문은 기존 스냅샷을 반환하며 다른 입력은409다. 옵션 공급은 별도 보류로 null이며 #340 Cart 성공 정리와 별개다.

판매자별 총액에서 해당 SKU 쿠폰 할인을 빼고 양수 금액을 합산한다. seller_id 순으로 `{orderRef}-S1` 번호를 발급하며 팀원 설정 `commerce.commission-rate`를 생성 시 seller_order에 보존한다. 임의 숫자 기본값은 없다. 설정 누락·잘못된 금액은 전체 TX를 rollback한다. 새 Payment가 양수 금액만 받는 제약은 새 API에만 적용하며 legacy 0원 경로를 제거하지 않는다.

### 3. 기한과 전달 복구

주문 생성600초 동안 PENDING과 재고·쿠폰 선점을 유지한다. Payment 생성/결제창 준비 응답을 받아도 그대로다. 사용자 인증 후 실제 승인 요청만 PROCESSING으로 바꾸고 최초 전환부터600초를 저장한다. 상태·기한·APPROVAL 전달 원문은 같은 Core TX, HTTP는 커밋 후다. 기존 enum PENDING_PAYMENT/PAYMENT_CONFIRMING과 저장된 생성기한은 보존한다.

전달기는 먼저 Payment를 조회한다. APPROVING/UNKNOWN/202 또는 응답 유실은 실패가 아니며 같은 원문으로 조회·재전달한다. 승인 응답에 없는 approvedAt은 조회/이벤트로 확인한다. 조회 대기 중 기한이 지나면 전송 직전 다시 만료하고 새 승인 POST 없이 조회만 한다. Payment CREATED expiresAt은 생성기한을 뜻하며 처리기한으로 바꾸지 않는다.

### 4. 결과·만료·늦은 승인

회원 guard와 묶음 잠금 뒤 DB시각으로 판정한다. 유효 PROCESSING 승인→PAID와 재고 차감·쿠폰 사용·Cart 출처가 있는 구매 SKU의 본인 Cart 행 전체 제거, 확정 실패→FAILED와 선점 반환이다. 정확 경계는 먼저 EXPIRED와 해제다. 만료는 Payment 실패 확정이 아니다.

종료 뒤 승인은 주문을 되살리지 않는다. `payment_relay_refund(REQUESTED)`와 `RefundRequested(LATE_APPROVAL, late-{paymentId})` outbox를 같은 TX에 한 번 저장한다. 중복 HTTP/Kafka 승인은 다시 재고를 변경하거나 환불요청을 만들지 않는다. RefundSucceeded/RefundFailed는 원문 금액·판매자 번호·요청키를 검증해 환불 상태만 확정한다. Kafka 발행 성공은 환불 완료가 아니다. Payment에 취소/만료를 통지하는 별도 계약은 없다.

### 5. 물리 이행과 호환

논리 Order는 payment_group, 항목은 orders다. V104 원본과 기존 PK/금액/Cart 출처는 유지한다. V105의 processing_expires_at과 legacy late_approval_refund(REQUIRED), V106의 seller_order/order_payment_relay/payment_relay_refund 및 PREPARE/APPROVAL 전달을 구분한다. Core 내부 FK만 추가하며 Payment DB는 변경하지 않는다. 신규 내부 주문번호만 PG-UUID39자로 생성하고 과거 번호는 보존한다.

기존 공개 `/v1/orders`, Cart checkout, payment-groups·환불 경로는 호환 유지하며 새 계약으로 이미 전환됐다고 쓰지 않는다. 기존 확인중 null 처리기한을 임의 역산하지 않는다. 과거 다른 V104/Payment V4 적용 환경은 별도 운영 이행이 필요해 배포 가능으로 주장하지 않는다.

### 6. 수량 환불과 쿠폰 복원

사용자 U1은 단가 우선 `min(단가×이번수량, 항목실결제액−이전성공환불액)`이며4000×3/쿠폰2000은4000/4000/2000이다. OrderRefundAmounts의 독립 계산/경계 검사를 유지한다. U2는 주문당쿠폰한장·선택항목하나·누적전량성공 때 원 유효기간 복원이다. 이 규칙을 **실제 신규 수량환불 실행 완료**라고 쓰지 않는다. 공개 어댑터/Payment 계약은 M3 후속이다.

기존 legacy 환불은 기존 항목전체 계약과 Payment 호출을 유지하며 성공 재고 복구를 SKU에 연결한다. Payment 금전 성공과 Core 로컬 반영은 서비스별 트랜잭션이므로 Core 롤백을 금전 취소로 확대 해석하지 않는다.

### 7. Payment 담당 경계

Payment 소유 구현·DTO·V4·계약을 새로 확장하지 않는다. 최신 dev의 ADR0337은 채택된 팀원 목표이며 M1/M2/M3 구현 완료가 아니다. 새 Payment 설정 원본·실제 구현 가용성·검증 범위는 [현재 작업](../../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md)에 있다. 최초 요구는 [인계 문서](../../../../worklog/plans/active/331-order-audit-fixes/payment-handoff.md)에만 남긴다.

### 8. 검증 상태

보존된 기존 구현의 a68 전체1362 통과, 504의 CI740(외부Kafka2스킵), 최종 감사1376중1실패는 각 당시 증거다. 독립 seller/Cart 계약 보완0146dc83의 실제PG/PaymentHTTP8건 통과도 범위 정정 전 증거다. 현재 변경의 검증은 [2026-11-16 워크로그](../../../../worklog/2026-11-16.md)에서 이전 증거와 구분한다. 실패·스킵·NO-SOURCE를 성공으로 합산하지 않는다.

### 9. 직접 합의와 구분하는 구현 선택

회원당 활성1건과 Payment 결과확정부터168h 미만 환불은 기존dev 제약이며 이번에 새로 직접 승인받은 정책으로 표시하지 않는다. Cart 담기 판매조건·0재고 판매시작·상품 전체중지 공개노출은 미답변이다. #340 Cart 성공 정리는 회원+SKU 기준으로 반영한다. #345/#346·공개 다중SKU/대표가/가격순·옵션은 후속/보류다. 실제 결제제공자·운영·배포·전체 정책 수용을 검증 완료로 표시하지 않는다.

## Order 로컬 도메인 상태와 기존 공개 호환

<!-- visual: relations -->

[상세설계](overview.md) · [ERD](erd.md) · [후속 요구](../../../../worklog/plans/active/331-order-audit-fixes/payment-handoff.md)

새 공개 주문서·팀원 계약 중계와 기존 legacy 경로를 함께 제공한다. 실제 환불 통합은 미완이며 계약 대역 검증과 구분한다.

작은 화면에서는 도표 안을 좌우로 이동해 양쪽 노드를 확인한다. 관련 항목 필터로 관계 수를 줄일 수 있다.

### 전이

| 출발 | 도착 | 조건·효과 |
|---|---|---|
| 내부 생성 요청 | PENDING | DB시각+600초·스냅샷·SKU선점을같은Core트랜잭션에저장 |
| 내부 PENDING | EXPIRED | 생성기한 도달·예약수량과 쿠폰을 한 번 반환. 외부 실패를 합성하지 않음 |
| 내부 PENDING | CANCELLED | 로컬취소·중복재고반환없음 |
| 새 PENDING | PENDING | Payment 생성·checkout 성공은 준비 정보만 저장하며 처리 기한을 시작하지 않음 |
| 새 PENDING | PROCESSING | 인증 후 승인 요청·첫 전환600초·APPROVAL 전달 원문을 같은 TX에 저장 |
| 새 PROCESSING | PAID/FAILED | 유효 기한의 확정 승인/실패만 적용. 202/UNKNOWN은 PROCESSING 유지 |
| 새 PROCESSING | EXPIRED | 정확 기한 도달·선점 해제, Payment 미확정 조회는 유지 |
| 새 종료 주문 + 승인 | 종료 주문 + 환불 요청 | payment_relay_refund REQUESTED와 RefundRequested outbox 같은 TX |
| 환불 REQUESTED | SUCCEEDED/FAILED | Payment 환불 결과 원문 검증·주문/재고 상태는 유지 |
| 공개 legacy PENDING_PAYMENT | PAYMENT_CONFIRMING | 첫 실행 접수에서 별도600초 기한과 HTTP 전달 원문을 같은 로컬TX에 저장 |
| 공개 legacy PAYMENT_CONFIRMING | PAID/FAILED | 처리기한 내 기존 Payment 확정결과를 멱등반영하고 SKU소진/해제 |
| 공개 legacy PAYMENT_CONFIRMING | EXPIRED | processing_expires_at 도달. UNKNOWN도 주문 선점은 한 번 해제하며 Payment 실패로 확정하지 않음 |
| EXPIRED/CANCELLED + 승인 성공 | EXPIRED/CANCELLED + 환불 필요 | 주문을 복구하지 않고 late_approval_refund(REQUIRED)와 결제결과를 같은TX에 기록 |
| 공개 legacy PAID | REFUNDED | 기존항목전체환불계약·성공확인뒤SKU복원 |

Product 중지·삭제는신규주문만차단하고기선점주문은원래기한안결제허용(사용자확정)이다. 새 order-sheets 입구만 policy1 중계를 소비하며 legacy 조회/금전 입구는 policy1을 소비하지 않는다. policy1/2진행중데이터환경의배포지원은이번범위가아니다.

새 PENDING/PROCESSING 각600초는 적용했다. 기한 도달은 주문 선점해제이며 PG실패 확정이 아니다. 새 Payment 계약의 Core 중계·RefundRequested/결과 소비를 제공한다. 실제 Payment 환불 통합은 미완이고 legacy REQUIRED는 환불 완료가 아니다. V105 이전 확인중 주문의 null 기한은 별도 이행이 필요하다. 수량환불은독립계산만남기며실제신규환불완료라표시하지않는다.

### 팀원 계약 중계의 현재 상태

새 `/v1/order-sheets` 준비는 PENDING 유지, 인증 후 승인 접수만 PROCESSING과 별도600초를 시작한다. PREPARE/APPROVAL 전달 원문과 상태는 같은 TX이며 HTTP는 커밋 후다. 202/UNKNOWN은 미확정, 기한 경계는 EXPIRED와 선점 해제다. 늦은 승인→payment_relay_refund REQUESTED와 RefundRequested outbox→결과 소비 SUCCEEDED/FAILED다. 주문은 종료 상태를 유지한다. legacy REQUIRED 기록과 새 원결제 환불표는 분리한다.

Core PostgreSQL/계약 대역·Kafka 검사와 실제 Payment 생성·조회·checkout 확인을 구분한다. 실제 환불 생산자·소비자는 아직 발견하지 못해 전체 통합은 미완이다. [현재 원본·남은 기준](../../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md).

## CommerceCore 주문·결제 묶음

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

확정 결과 재수신은 동일 결과인지 검사하고 중복 재고 반영을 막는다. 새 PAYMENT_CONFIRMING은 첫 실행 접수부터600초까지만 선점을 유지한다. 공개 enum은 PROCESSING 의미의 기존 이름을 보존한다. OrderStatus 주석에 남은 제안 표현보다 실제 서비스와 조건부 UPDATE를 기준으로 읽었다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PENDING_PAYMENT | 가격·수량·회원·멱등 키를 확인하고 재고·쿠폰 예약과 주문을 영속화한다. |
| PENDING_PAYMENT | PAYMENT_CONFIRMING | 본인 묶음·만료 여부를 확인하고 구매 guard/행 잠금 안에서 영속 Payment 전달 요청 및 별도 processing_expires_at=DB시각+600초와 함께 전환한다. 재전송은 연장하지 않는다. |
| PENDING_PAYMENT | CANCELLED | 본인 취소. 확인 중·확정 주문은 이 경로로 취소하지 않는다. 예약 재고·쿠폰을 해제한다. |
| PENDING_PAYMENT | EXPIRED | 만료 시각 경과를 재확인하고 예약을 해제한다. |
| PAYMENT_CONFIRMING | EXPIRED | 처리 기한 도달. 재고·쿠폰 선점은 해제하지만 Payment 실패로 확정하지 않는다. |
| EXPIRED/CANCELLED | 같은 종료 상태 | 늦은 SUCCESS는 환불 필요 REQUIRED와 결과만 원자적으로 저장한다. 재선점하지 않는다. |
| PAYMENT_CONFIRMING | PAID | 기한 내 동일 전달 원문의 SUCCESS 확정 결과. 예약 재고 소비·쿠폰 사용·조건부 장바구니 삭제와 결과 저장을 함께 처리한다. |
| PAYMENT_CONFIRMING | FAILED | FAILED 확정 결과. 재고·쿠폰 예약을 복원한다. UNKNOWN은 이 경로로 보내지 않는다. |
| PAID | REFUNDED | 성공한 환불 대상 주문만 변경·재고 복원. 묶음은 모든 자식 주문이 환불된 경우에만 REFUNDED가 된다. |

### 구현과 검증 근거

- [PaymentGroupLifecycleService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/PaymentGroupLifecycleService.java)
- [OrderPaymentOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderPaymentOutcomeService.java)
- [RefundOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundOutcomeService.java)
- [OrderJpaRepository.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/repository/OrderJpaRepository.java)

관련 기존 테스트: [PaymentGroupIntegrationTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/order/service/PaymentGroupIntegrationTest.java) · `PaymentDeliveryBoundaryPostgresTest.java`(#347에서 삭제).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

### 2026-11-16 당시 PR 경계

새 공개 주문은 기본600초이고 첫 실행 접수에서 별도600초 처리 기한을 저장한다. 기존 생성 기한과 V105 이전 확인중 null 처리기한은 유지한다. 당시 새 Payment의 준비 생성/인증 후 승인 중계는 미완이었다. 현재 구현 상태는 문서 상단의 정정을 따른다. 늦은 승인의 REQUIRED는 환불 요청 전 기록이며 Kafka 발행/Payment 환불 완료가 아니다. [현재 작업](../../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [현재 검증](../../../../worklog/2026-11-16.md). 전체설계 미충족·Draft·배포 불가다.

## CommerceCore 결제 전달·결과 반영

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

Core의 payment_delivery_request는 실행 원본이 아니라 영속 전달·결과 사본이다. 커밋 후 전달하며 복구 스케줄러가 같은 원문을 재전달한다. terminal 이후 늦은 PROCESSING/UNKNOWN으로 덮어쓰지 않는다. 전달 생성/재시도 시각과 due 조회는 같은 DB시각을 사용해 JVM 시각 차이로 새 요청이 미래로 보이는 일을 막는다. PENDING·TIMEOUT 호환 값은 신규 흐름에 그리지 않는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 주문을 확인 중으로 바꾸는 트랜잭션에서 request_id와 전달 원문을 저장한다. |
| PROCESSING | UNKNOWN | Payment가 유효한 미확정 결과를 반환하면 저장한다. 전송 예외만으로 확정 실패를 만들지 않는다. |
| UNKNOWN | PROCESSING | Payment가 원문과 일치하는 PROCESSING 응답을 반환하면 미확정 스냅샷을 갱신할 수 있다. |
| PROCESSING | SUCCESS | requestId·결제/주문/묶음 ID·금액·시각을 검증한 확정 결과를 주문 처리와 같은 트랜잭션에 기록한다. |
| UNKNOWN | SUCCESS | 재전달·조회·이벤트로 확정 결과를 확인하면 기한 내 주문 성공 또는 기한 후 환불 필요 사실과 함께 반영한다. |
| PROCESSING | FAILED | 동일 원문의 확정 실패만 반영한다. |
| UNKNOWN | FAILED | 조회로 확정 실패를 확인한 경우. 단순 통신 시간 초과와 구분한다. |

### 구현과 검증 근거

- [PaymentDeliveryStore.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryStore.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [OrderPaymentOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderPaymentOutcomeService.java)

관련 기존 테스트: [PaymentResultHandlerTest.java](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandlerTest.java) · `PaymentDeliveryBoundaryPostgresTest.java`(#347에서 삭제).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

### 2026-11-16 당시 PR 경계

새 공개 주문 생성은 기본600초이며 첫 승인 실행 접수에서 별도600초 기한을 저장한다. 처리 기한 도달은 EXPIRED와 선점해제이며 Payment 실패 확정이 아니다. legacy 늦은 승인은 REQUIRED 기록이며 새 계약 경로는 환불 요청 outbox와 결과 소비를 제공한다. 실제 Payment 환불 통합은 미완이다. 기존 공개 결제/환불 입구를 제거하거나503으로 막지 않는다. [현재 작업](../../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [새 검증](../../../../worklog/2026-11-16.md). 전체설계 미충족·Draft·배포 불가다.

### 팀원 계약 중계의 현재 상태

새 `/v1/order-sheets` 준비는 PENDING 유지, 인증 후 승인 접수만 PROCESSING과 별도600초를 시작한다. PREPARE/APPROVAL 전달 원문과 상태는 같은 TX이며 HTTP는 커밋 후다. 202/UNKNOWN은 미확정, 기한 경계는 EXPIRED와 선점 해제다. 늦은 승인→payment_relay_refund REQUESTED와 RefundRequested outbox→결과 소비 SUCCEEDED/FAILED다. 주문은 종료 상태를 유지한다. legacy REQUIRED 기록과 새 원결제 환불표는 분리한다.

Core PostgreSQL/계약 대역·Kafka 검사와 실제 Payment 생성·조회·checkout 확인을 구분한다. 실제 환불 생산자·소비자는 아직 발견하지 못해 전체 통합은 미완이다. [현재 원본·남은 기준](../../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md).

### 새 Payment와 기존 Mock의 계약 구분

2026-11-17 Core `ebea69d6`와 팀원 `feat/#349-payment-recovery`의 `800b41fd`를 대조했다. 새 주문서의 요청은 이미 팀원의 새 DTO와 일치한다. dev에 남아 있는 기존 `PaymentCommand`는 별도 실행 계약이므로 필드 이름만 바꾸거나 새 주문서를 그 DTO로 되돌리지 않는다.

| 단계 | Core 요청 | 팀원 새 Payment 수신 |
|---|---|---|
| 생성 POST `/v1/internal/payments` | `Create`: orderRef, buyerId, amount, expiresAt, sellerAmounts | `CreatePaymentRequest`. 구매자·판매자 ID는 UUID, 시각은 오프셋 포함 ISO 문자열, 판매자별 금액과 수수료를 받는다. 준비만 하며 PG 승인을 실행하지 않는다. |
| 결제창 POST `/{orderRef}/checkouts` | 본문 없음 | 새 pgOrderId, amount, expiresAt 반환 |
| 조회 GET `/{orderRef}` | 문자열 주문 참조 | ApiResponse data의 상태·금액·환불액·승인 시각 반환 |
| 승인 POST `/{orderRef}/approval` | `Approval`: buyerId, paymentKey, pgOrderId, amount | `ApprovalRequest`. 200 APPROVED, 202 PENDING과 Location, 422 DECLINED를 구분한다. |
| 기존 Mock 실행 | `PaymentCommand`: requestId, paymentId, orderId, paymentGroupId, referenceNumber, amount, scenario | 생성 준비가 아니라 기존 결제 실행이다. 조회도 숫자 paymentId이며 응답 봉투가 다르다. |

새 Payment 연결 주소는 `commercecore.payment-relay.base-url`로 명시한다. 기존 Mock을 함께 검증할 때는 `commercecore.payment-client.base-url`과 서로 다른 주소를 사용한다. relay 주소를 생략하면 기존 client 주소(그것도 없으면 localhost:8086)를 사용하므로 구형 Mock 앱을 새 Payment로 오인하지 않는다. 서비스 토큰은 `commercecore.payment-client.service-token` 또는 `COMMERCECORE_PAYMENT_SERVICE_TOKEN`을 사용하며 실제 Payment 설정과 일치해야 한다.

팀원 새 Payment는 PR #352–#359의 별도 작업이다. 이 문서·회귀 테스트의 병합이 해당 구현의 dev 병합이나 배포를 뜻하지 않는다. [계약 회귀 테스트](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/payment/relay/HttpPaymentRelayClientTest.java)와 [이번 검증 범위](../../../../worklog/2026-11-17-payment-contract.md)를 구분해 확인한다. 취소 요청 #360과 신규 주문서 일반 환불은 별도다.


## CommerceCore 환불 접수·결과 반영

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

commercecore.refunds.execution-enabled가 꺼져 있으면 REFUND 전달을 하지 않는다. 공개 API 사용 가능 여부와 실행 토글은 계약·설정을 따로 확인한다. 중복 결과와 누적 환불 금액을 확인하며 일부 환불만으로 결제 묶음 전체를 환불 완료로 만들지 않는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 성공한 원결제·소유자·기간·대상·누적 금액·멱등 원문을 검사하고 refund_request와 대상을 영속화한다. |
| PROCESSING | UNKNOWN | Payment의 유효한 미확정 응답을 전달 기록과 환불 요청에 반영한다. |
| UNKNOWN | PROCESSING | Payment의 현재 미확정 스냅샷에 따라 다시 PROCESSING을 기록할 수 있다. |
| PROCESSING | SUCCESS | 원문·금액·대상 검증 후 주문 PAID→REFUNDED, 재고 복원을 같은 트랜잭션에서 처리한다. |
| UNKNOWN | SUCCESS | 조회·이벤트로 확정된 성공 결과를 같은 결과 처리 경로로 반영한다. |
| PROCESSING | FAILED | 확정 실패를 기록하며 성공 환불의 재고 복원을 수행하지 않는다. |
| UNKNOWN | FAILED | 실행 결과가 실패로 확정된 경우에만 변경한다. |

### 구현과 검증 근거

- [RefundIntakeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundIntakeService.java)
- [RefundOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundOutcomeService.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)

관련 기존 테스트: `RefundDispatchDisabledPostgresTest.java`(#347에서 삭제) · `RefundExecutionPostgresTest.java`(#347에서 삭제).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

### 2026-11-16 당시 PR 경계

새 공개 주문 생성은 기본600초이며 첫 승인 실행 접수에서 별도600초 기한을 저장한다. 처리 기한 도달은 EXPIRED와 선점해제이며 Payment 실패 확정이 아니다. 당시 늦은 승인은 REQUIRED 기록까지 제공했고 새 Payment 생성·승인·조회/RefundRequested/환불 결과 연동은 미완이었다. 현재 Core 중계 구현과 실제 provider 부재는 문서 상단에서 구분한다. 기존 공개 결제/환불 입구를 제거하거나503으로 막지 않는다. [현재 작업](../../../../worklog/plans/active/331-order-audit-fixes/processing-deadline.md) · [새 검증](../../../../worklog/2026-11-16.md). 전체설계 미충족·Draft·배포 불가다.

## #340 Cart 출처와 성공 정리

Cart 출처 ID/version은 주문 생성 시 선택 검증과 기존 선택 환불 계약의 식별 자료로 보존한다. 성공 삭제에는 회원 ID + 해당 주문항목의 SKU만 사용하므로 결제 중 수량 변경·삭제 후 재추가도 정리한다. 같은 상품의 다른 SKU·다른 회원·직접 구매의 Cart는 보존한다. 기존 terminal 결과/승인 사실 및 상태 전이로 최초 성공만 반영하며 중복 성공 이후 재담기는 보존한다. 실패·취소·만료·늦은 승인에서는 Cart를 정리하지 않는다. Cart→SKU 잠금 순서와 상태·재고·쿠폰·알림의 원자성은 유지한다. [Cart 결정](../cart/overview.md#2026-11-18-340-성공-정리-결정).
