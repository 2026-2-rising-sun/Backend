# ADR 0337-3: 결제 결과는 outbox 이벤트로 알리고 판단은 Commerce가 한다

결정 상태: 채택
구현 상태: [결제 요구사항](../design-docs/services/payment/requirements/payment.md), [환불 요구사항](../design-docs/services/payment/requirements/refund.md)에서 추적
관련 자료: [이벤트 계약](../design-docs/services/payment/design.html#contract) · [Saga](../design-docs/services/payment/design.html#saga)

## 문제

승인 결과는 동기 응답, 지연 응답(202 뒤), 결과 불명 복구로 늦게 확정될 수 있다. 주문이 이미 만료·취소된 뒤 승인이 확정되면 돈만 빠져나간다.

## 결정과 대안

- Payment는 상태 변경과 같은 트랜잭션에서 공통 outbox에 이벤트를 쓴다. 받는 쪽은 업무 키(UNIQUE·조건부 UPDATE)로 한 번만 반영한다.
- Payment는 "토스에서 승인됐다"는 사실만 알린다. 주문을 결제 완료로 바꿀지 환불할지는 주문 상태를 가진 Commerce가 판단한다(코레오그래피 Saga).
- 늦은 승인은 Commerce가 `RefundRequested`(사유 LATE_APPROVAL)를 보내고 Payment가 전액 환불한다.
  - 정정(2026-11-21): 고객 환불과 시스템 보상(늦은 승인·승인 결과 불명)은 모두 `commerce.refund.requests.v1`의 `RefundRequested` 하나로 오고 `purpose`(USER_REFUND·SYSTEM_COMPENSATION)로 구분한다. Payment는 `payment.refund.results.v1`의 `RefundResultRecorded`로 답한다. 결과에는 받은 요청 payload를 그대로 싣고 확정 결과(SUCCEEDED·FAILED·NO_CHARGE)만 보낸다. UNKNOWN은 보내지 않는다. 같은 `refundRequestId`에 다른 본문이 오면 결과를 보내지 않는다. 원문과 다른 결과를 Core가 거부하면 파티션이 막히기 때문이다. 필드 의미는 [결과 계약](../../contracts/events/payment-refund-results.yaml)과 [환불 요구사항](../design-docs/services/payment/requirements/refund.md)에 있다(#384).
- Payment는 주문 만료·취소 이벤트를 구독하지 않는다. 자기 만료 시각으로 CREATED 결제를 닫는다.
- 대안: 오케스트레이터 또는 Payment가 주문 상태를 판단. 판단 주체가 둘이 되어 택하지 않았다.

## 영향

- Commerce가 "주문은 닫혔는데 승인됨" 처리를 빠뜨리면 환불이 누락된다. 계약 테스트와 지표(0행 처리 수 = 환불 요청 수)로 막는다.
- 남은 불일치는 거래 대사가 잡는다.
