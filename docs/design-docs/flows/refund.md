# 환불 서비스 간 흐름

<!-- visual: relations -->

기준: 2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`의 Core 코드와 그 뒤 #378(사용자 환불·시스템 보상 분리), #384(환불 결과 계약) 변경. Payment 환불 연동 완료나 실제 금전 환불을 확인한 문서가 아니다.

[시각 자료](refund.html) · [주문·결제](order-payment.md) · [확인 근거와 잔여 과제](../../worklog/2026-11-20-cdc-refund-status.md)

## 현재 dev: 기존 사용자 환불

[RefundIntakeService](../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundIntakeService.java)는 legacy 실행 주문만 접수한다. 새 주문서의 사용자 환불을 이 입구가 제공한다고 해석하지 않는다. 소유권·원결제 성공·확정 시각부터 168시간 미만·대상·누적 금액·멱등 원문을 확인한다. 항목 전체 환불 계약이며 신규 수량 환불 완료를 뜻하지 않는다.

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| legacy PAID | refund_request PROCESSING | 원결제 HTTP 조회는 Core TX 밖에서 하고 잠금 아래 연결을 재검증한다. 대상·금액·전달 원문을 저장한다. |
| 영속 REFUND 전달 | 기존 Payment HTTP | execution-enabled일 때 커밋 후 고정 requestId/원문을 /v1/internal/refunds로 전달한다. |
| PROCESSING | UNKNOWN | 유효한 미확정 결과를 보존하고 재전달·조회한다. 시간 경과만으로 실패로 확정하지 않는다. |
| PROCESSING/UNKNOWN | SUCCESS | 검증된 성공 대상만 REFUNDED로 바꾸고 SKU 재고를 복구한다. 중복 결과는 다시 복구하지 않는다. |
| PROCESSING/UNKNOWN | FAILED | 확정 실패만 반영하며 성공 재고 복원을 하지 않는다. |
| 일부 항목 환불 | 묶음 PAID 유지 | 모든 자식 주문이 REFUNDED일 때만 묶음도 REFUNDED가 된다. |

현재 서비스 application.yml의 `commercecore.refunds.api-enabled`와 `execution-enabled`는 true다. 코드 생성자의 기본 false와 구분한다. 다만 dev Payment에서 기존 내부 환불 실행 API는 삭제됐고 이를 대체할 환불 provider가 없다. 따라서 토글이 켜졌거나 Core에 접수/결과 코드가 있다는 사실로 실제 환불 가능을 주장하지 않는다. 구 Mock 시나리오·과거 테스트 성공도 현재 Payment 실행 증거가 아니다.

## 현재 dev: 종료 주문의 늦은 승인

새 주문서가 EXPIRED/CANCELLED/FAILED가 된 뒤 승인 사실을 확인하면 주문을 되살리지 않는다. [PaymentRelayService](../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/relay/PaymentRelayService.java)는 `payment_relay_refund(REQUESTED)`와 `late-{paymentId}` 요청을 같은 TX에 저장한다. 토픽은 구 하이픈 이름 `commerce.refund-requests.v1`, payload는 refundRequestId/paymentId/orderRef/reason 네 필드다. Core는 `payment.refund-results.v1`의 RefundSucceeded/RefundFailed를 검증해 SUCCEEDED/FAILED를 기록하며 주문·재고의 종료 상태를 유지한다. legacy의 `late_approval_refund(REQUIRED)`는 별도 기록이다.

Payment의 요청 consumer와 결과 producer는 #385로 dev에 있다. Kafka 발행·계약 검사는 Payment 접수나 금전 환불 완료가 아니다.

## 사용자 환불과 시스템 보상의 분리

사용자 요청과 시스템의 결제 미확정을 같은 취소 상태로 취급하지 않는다.

| 구분 | 현재 구현 | 완료 전제 |
|---|---|---|
| 사용자 환불 | 사용자 요청은 CANCEL_PENDING, 성공 수량에 따라 REFUNDED/PARTIALLY_REFUNDED | 원요청·금액·대상과 맞는 결과만 반영 |
| 시스템 보상 | 명시적 Payment UNKNOWN 또는 최초 PROCESSING부터 600초 경과는 COMPENSATION_PENDING | 시작 때 재고·쿠폰을 해제하지 않고 성공 결과 뒤 한 번 해제·CANCELLED |
| 공통 요청/결과 | commerce.refund.requests.v1 → payment.refund.results.v1 | dot 토픽과 purpose·금액·판매자별 원문을 실제 provider가 함께 구현 |
| 종료 뒤 승인 | 주문 부활 없이 고정된 요청 ID로 보상 재요청·대조 | 원결제 ID 연결과 결과 원문 검증 유지 |

## 결과 계약 (#384)

Payment 결과 계약은 [payment-refund-results.yaml](../../../contracts/events/payment-refund-results.yaml)이다. 구 `payment.refund-results.v1`·`RefundExecutionResolved` 계약은 이 계약으로 바뀌었다.

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| Core `RefundRequested` | Payment 환불 PROCESSING | `commerce.refund.requests.v1`, 키 orderRef. 고객 환불(USER_REFUND)과 시스템 보상(SYSTEM_COMPENSATION)이 같은 토픽이다. `refundRequestId`로 한 번만 접수하고 원문을 저장한다. |
| Payment 환불 | `RefundResultRecorded` | `payment.refund.results.v1`, 키 orderRef. payload는 요청 원문 그대로에 `executionId`(UUID, 요청당 고정)·`resultVersion`(1부터)·`status`·`refundedAmount`·`resolvedAt`·`nextActionAt`(null)·`failureCode`를 더한다. |
| 요청 필드 | 계약 | `paymentId`는 USER_REFUND·SYSTEM_COMPENSATION 모두 필수다. 사유는 USER_REFUND의 `CUSTOMER_REQUEST`, SYSTEM_COMPENSATION의 `APPROVAL_UNKNOWN`·`LATE_APPROVAL`만 쓴다. |
| 결제 없음·불일치 | FAILED 결과 | 돈을 움직이지 않는다. `failureCode` `PAYMENT_NOT_FOUND`·`PAYMENT_MISMATCH`. |
| 같은 ID·같은 본문 | 저장 결과 재발행 | 같은 `resultVersion`·같은 payload, 새 eventId. 결과가 아직 없으면 보내지 않는다. |
| 같은 ID·다른 본문 | 결과 없음 | Payment가 REQUEST_CONFLICT 수신으로 기록하고 ERROR 로그. Core가 원문과 다른 결과를 거부해 파티션이 막히는 것을 피한다(#389). |
| 시스템 보상 FAILED | 더 높은 버전 SUCCEEDED·NO_CHARGE | 시스템 보상만 허용한다. NO_CHARGE는 `failureCode: NO_CHARGE`, 환불 금액 0. |
| 결과 불명 | 결과 없음 | Payment는 UNKNOWN을 보내지 않는다(`status`는 SUCCEEDED·FAILED·NO_CHARGE). 확정될 때 보낸다. `nextActionAt`은 항상 null이다. |
| 미승인 종료 | NO_CHARGE | 시스템 보상만 쓴다. `failureCode: NO_CHARGE`, 환불 금액 0. |

Payment의 요청 consumer와 결과 producer는 #385로 dev에 있다. Core는 paymentId 없는 보상 요청을 만들지 않는다. Payment 파서는 계약보다 넓게 받으며(#402에서 좁힌다), 계약 밖 요청은 FAILED(`PAYMENT_ID_REQUIRED`)로 답할 수 있다. 이 문서의 계약 검사는 실제 토스 환불 완료를 증명하지 않는다.

[현재 Core 모듈](../services/commercecore/order/overview.md) · [Payment 소유 설계](../services/payment/overview.md) · [계약 원본](../../../contracts/events). Payment 설계 링크는 담당자의 목표 자료이며 이 문서가 구현 완료를 선언하지 않는다.
