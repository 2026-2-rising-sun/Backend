# ADR 0337-1: 결제는 Commerce를 거치는 내부 API로만 받는다

결정 상태: 제안
구현 상태: [결제 요구사항](../requirements/payment.md)에서 추적
관련 자료: [결제 재설계 실행 계획](../exec-plans/active/337-payment-redesign/README.md) · [이벤트 스토밍 API](../exec-plans/active/337-payment-redesign/event-storming-v41.html#api)

## 문제

결제 승인 직전에 본인 확인과 주문 상태 확인이 필요하다. 결제 진행 중에 주문이 취소되면 돈은 나갔는데 주문이 없는 상황이 생긴다.

## 결정과 대안

- 프론트는 Commerce만 호출한다. Payment API는 `/v1/internal/*`(서비스 토큰)뿐이고 Gateway에 열지 않는다. 경로는 팀 내부 API 관례를 따른다.
- Commerce가 승인을 중계하면서 주문을 "결제 확인 중"으로 잠근다. Payment는 Commerce가 넘긴 구매자 ID로 다시 확인한다.
- Payment는 주문 구조를 모르고 불투명한 orderRef와 판매자별 금액만 받는다.
- 대안: 프론트가 Payment를 직접 호출. Payment에 구매자 JWT 검증·공개 라우트·주문 닫기 API가 필요하고, 결제 중 주문 취소를 막기 어려워 택하지 않았다.

## 영향

- Commerce가 내려가 있으면 인증을 마친 결제도 승인할 수 없다. 토스 승인 제한 10분 안에 복구되지 않으면 구매자는 다시 결제한다.
- PG가 느리면 Commerce 스레드가 요청마다 최대 10초 묶인다. 승인 대기는 8초로 끊어 202를 먼저 준다([ADR 0337-5](0337-5-pg-timeout-and-unknown.md)). 토스 호출 동시 실행 수는 지금 제한하지 않는다(부하 문제가 보이면 도입).
- 승인 입구가 Commerce 하나라서 Payment는 주문 취소 이벤트를 구독하지 않는다([ADR 0337-3](0337-3-payment-result-delivery.md)). 리다이렉트 없이 돈이 움직이는 결제수단을 붙이면 다시 판단한다.
