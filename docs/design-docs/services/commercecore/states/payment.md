# CommerceCore 결제 전달·결과 반영

<!-- visual: relations -->

기준: #331 Core V104·Payment V4 구현. 기존 저장 행의 버전0, 신규 주문서 버전1, 신규 구 API 주문 버전2를 구분한다. 버전1/2는 모두 최초10분이며 기존 행의 저장기한은 바꾸지 않는다. 실제 검증 결과·전체 회귀 진행 상태는 [워크로그](../../../../worklog/2026-11-13.md)를 따른다.

[시각 자료](payment.html)

## 범위와 불변조건

Core의 payment_delivery_request는 실행 원본이 아니라 영속 전달·결과 사본이다. 커밋 후 전달하며 복구 스케줄러가 같은 원문을 재전달한다. terminal 이후 늦은 PROCESSING/UNKNOWN으로 덮어쓰지 않는다. PENDING·TIMEOUT 호환 값은 신규 흐름에 그리지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 버전0/2 주문을 확인 중으로 바꾸거나 버전1 주문의 기한을 유지하는 트랜잭션에서 request_id와 전달 원문을 저장한다. 버전2도 확인 중/UNKNOWN에서 최초10분 기한을 연장하지 않는다. |
| PROCESSING | UNKNOWN | Payment가 유효한 미확정 결과를 반환하면 저장한다. 전송 예외만으로 확정 실패를 만들지 않는다. |
| UNKNOWN | PROCESSING | Payment가 원문과 일치하는 PROCESSING 응답을 반환하면 미확정 스냅샷을 갱신할 수 있다. |
| PROCESSING | SUCCESS | requestId·결제/주문/묶음 ID·금액·시각을 검증한 확정 결과를 주문 처리와 같은 트랜잭션에 기록한다. |
| UNKNOWN | SUCCESS | 재전달·조회·이벤트로 확정 결과를 확인하면 기한 안이면 주문 성공, 버전1 만료 뒤이면 보상 의도와 함께 반영한다. |
| PROCESSING | FAILED | 동일 원문의 확정 실패만 반영한다. |
| UNKNOWN | FAILED | 조회로 확정 실패를 확인한 경우. 단순 통신 시간 초과와 구분한다. |

## 구현과 검증 근거

- [PaymentDeliveryStore.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryStore.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [OrderPaymentOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderPaymentOutcomeService.java)

관련 기존 테스트: [PaymentResultHandlerTest.java](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandlerTest.java) · [PaymentDeliveryBoundaryPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryBoundaryPostgresTest.java).

테스트 링크는 검증 경로다. 실제 실행 환경·건수·실패·스킵은 워크로그에서 구분한다.

## 버전1 종료와 보상

Order의10분 만료와 order_payment_closure 저장은 같은 Core 트랜잭션이다. 외부 HTTP 종료 전달은 커밋 뒤 재시도한다. 독립 Payment의 payment_order_control은 결제 생성·종료·호출 시작이 동일 paymentId/paymentRequestId를 잠근다. 이미 호출 중/UNKNOWN은 실제 결과 조회로 수렴한다. 늦은SUCCESS는 원결제 전액 보상이며0원에는 실제PG환불/신규환불원장이 없다. [최소 계약](../../../../exec-plans/active/331-order-module/payment-closure-contract.md)을 따른다.
