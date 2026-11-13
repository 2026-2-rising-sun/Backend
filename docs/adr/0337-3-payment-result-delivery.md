# ADR 0337-3: 결제 결과는 outbox 이벤트로 알리고 판단은 Commerce가 한다

결정 상태: 제안
구현 상태: [결제 요구사항](../requirements/payment.md), [환불 요구사항](../requirements/refund.md)에서 추적
관련 자료: [이벤트 계약](../exec-plans/active/337-payment-redesign/event-storming-v41.html#contract) · [Saga](../exec-plans/active/337-payment-redesign/event-storming-v41.html#saga)

## 문제

승인 결과는 동기 응답, 지연 응답(202 뒤), 결과 불명 복구로 늦게 확정될 수 있다. 주문이 이미 만료·취소된 뒤 승인이 확정되면 돈만 빠져나간다.

## 결정과 대안

- Payment는 상태 변경과 같은 트랜잭션에서 공통 outbox에 이벤트를 쓴다. 받는 쪽은 업무 키(UNIQUE·조건부 UPDATE)로 한 번만 반영한다.
- Payment는 "토스에서 승인됐다"는 사실만 알린다. 주문을 결제 완료로 바꿀지 환불할지는 주문 상태를 가진 Commerce가 판단한다(코레오그래피 Saga).
- 늦은 승인은 Commerce가 `RefundRequested`(사유 LATE_APPROVAL)를 보내고 Payment가 전액 환불한다.
- Payment는 주문 만료·취소 이벤트를 구독하지 않는다. 자기 만료 시각으로 CREATED 결제를 닫는다.
- 대안: 오케스트레이터 또는 Payment가 주문 상태를 판단. 판단 주체가 둘이 되어 택하지 않았다.

## 영향

- Commerce가 "주문은 닫혔는데 승인됨" 처리를 빠뜨리면 환불이 누락된다. 계약 테스트와 지표(0행 처리 수 = 환불 요청 수)로 막는다.
- 남은 불일치는 거래 대사가 잡는다.
