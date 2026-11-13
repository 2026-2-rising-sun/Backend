# CommerceCore 결제 전달·결과 반영

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](payment.html)

## 범위와 불변조건

Core의 payment_delivery_request는 실행 원본이 아니라 영속 전달·결과 사본이다. 커밋 후 전달하며 복구 스케줄러가 같은 원문을 재전달한다. terminal 이후 늦은 PROCESSING/UNKNOWN으로 덮어쓰지 않는다. PENDING·TIMEOUT 호환 값은 신규 흐름에 그리지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 주문을 확인 중으로 바꾸는 트랜잭션에서 request_id와 전달 원문을 저장한다. |
| PROCESSING | UNKNOWN | Payment가 유효한 미확정 결과를 반환하면 저장한다. 전송 예외만으로 확정 실패를 만들지 않는다. |
| UNKNOWN | PROCESSING | Payment가 원문과 일치하는 PROCESSING 응답을 반환하면 미확정 스냅샷을 갱신할 수 있다. |
| PROCESSING | SUCCESS | requestId·결제/주문/묶음 ID·금액·시각을 검증한 확정 결과를 주문 처리와 같은 트랜잭션에 기록한다. |
| UNKNOWN | SUCCESS | 재전달·조회·이벤트로 확정 결과를 확인하면 주문 성공과 함께 반영한다. |
| PROCESSING | FAILED | 동일 원문의 확정 실패만 반영한다. |
| UNKNOWN | FAILED | 조회로 확정 실패를 확인한 경우. 단순 통신 시간 초과와 구분한다. |

## 구현과 검증 근거

- [PaymentDeliveryStore.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryStore.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [OrderPaymentOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderPaymentOutcomeService.java)

관련 기존 테스트: [PaymentResultHandlerTest.java](../../../../../services/commercecore-service/src/test/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandlerTest.java) · [PaymentDeliveryBoundaryPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryBoundaryPostgresTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
