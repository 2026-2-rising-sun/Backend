# 환불 서비스 간 흐름

<!-- visual: relations -->

기준: 2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`의 Core 코드. [#378](https://github.com/2026-2-rising-sun/Backend/pull/378)(head `228f33faaa5e9519ffd74ec12b2878825fa85dde`)은 미병합 Draft이며 아래 목표 흐름은 dev 동작이 아니다. Payment 환불 연동 완료나 실제 금전 환불을 확인한 문서가 아니다.

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

현재 dev Payment에는 이 요청의 실행 consumer와 해당 환불 결과 producer가 없다. Kafka 발행·Core 결과 대역 검사는 Payment 접수나 금전 환불 완료가 아니다.

## 미병합 #378: 사용자 환불과 시스템 보상의 분리

아래는 #378의 Core 구현·목표이며 현재 dev에 적용되지 않았다. 사용자 요청과 시스템의 결제 미확정을 같은 취소 상태로 취급하지 않는다.

| 구분 | #378 목표·구현 | 완료 전제 |
|---|---|---|
| 사용자 환불 | 사용자 요청은 CANCEL_PENDING, 성공 수량에 따라 REFUNDED/PARTIALLY_REFUNDED | 원요청·금액·대상과 맞는 결과만 반영 |
| 시스템 보상 | 명시적 Payment UNKNOWN 또는 최초 PROCESSING부터 600초 경과는 COMPENSATION_PENDING | 시작 때 재고·쿠폰을 해제하지 않고 성공 결과 뒤 한 번 해제·CANCELLED |
| 공통 요청/결과 | commerce.refund.requests.v1 → payment.refund.results.v1 | dot 토픽과 purpose·금액·판매자별 원문을 실제 provider가 함께 구현 |
| 종료 뒤 승인 | 주문 부활 없이 고정된 요청 ID로 보상 재요청·대조 | 원결제 ID 연결과 결과 원문 검증 유지 |

Payment provider가 새 요청 consumer/결과 producer를 제공하지 않으며, 별도 provider 쪽 구 `payment.refund-results.v1/RefundExecutionResolved` 계약도 새 dot 결과와 일치하지 않는다. 이 상태로는 CANCEL_PENDING/COMPENSATION_PENDING이 끝나지 않고 시스템 보상은 재고·쿠폰·활성 구매 자리를 계속 점유한다. 원요청에 paymentId가 없고 결과에만 새 ID가 있는 경우 현재 조회 계약으로 안전한 연결 복구도 보장할 수 없다. 두 문제는 실제 병합 차단 사유이며 Draft 표시·단위 테스트로 해소되지 않는다.

[현재 Core 모듈](../services/commercecore/order/overview.md) · [Payment 소유 설계](../services/payment/overview.md) · [계약 원본](../../../contracts/events). Payment 설계 링크는 담당자의 목표 자료이며 이 문서가 구현 완료를 선언하지 않는다.
