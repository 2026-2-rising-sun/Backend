# CommerceCore 주문·결제 묶음

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](order.html)

## 범위와 불변조건

확정 결과 재수신은 동일 결과인지 검사하고 중복 재고 반영을 막는다. PAYMENT_CONFIRMING은 결과 미확정 동안 유지한다. OrderStatus 주석에 남은 제안 표현보다 실제 서비스와 조건부 UPDATE를 기준으로 읽었다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PENDING_PAYMENT | 가격·수량·회원·멱등 키를 확인하고 재고·쿠폰 예약과 주문을 영속화한다. |
| PENDING_PAYMENT | PAYMENT_CONFIRMING | 본인 묶음·만료 여부를 확인하고 구매 guard/행 잠금 안에서 영속 Payment 전달 요청과 함께 전환한다. |
| PENDING_PAYMENT | CANCELLED | 본인 취소. 확인 중·확정 주문은 이 경로로 취소하지 않는다. 예약 재고·쿠폰을 해제한다. |
| PENDING_PAYMENT | EXPIRED | 만료 시각 경과를 재확인하고 예약을 해제한다. |
| PAYMENT_CONFIRMING | PAID | 동일 전달 원문의 SUCCESS 확정 결과. 예약 재고 소비·쿠폰 사용·조건부 장바구니 삭제와 결과 저장을 함께 처리한다. |
| PAYMENT_CONFIRMING | FAILED | FAILED 확정 결과. 재고·쿠폰 예약을 복원한다. UNKNOWN은 이 경로로 보내지 않는다. |
| PAID | REFUNDED | 성공한 환불 대상 주문만 변경·재고 복원. 묶음은 모든 자식 주문이 환불된 경우에만 REFUNDED가 된다. |

## 구현과 검증 근거

- [PaymentGroupLifecycleService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/PaymentGroupLifecycleService.java)
- [OrderPaymentOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/service/OrderPaymentOutcomeService.java)
- [RefundOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundOutcomeService.java)
- [OrderJpaRepository.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/repository/OrderJpaRepository.java)

관련 기존 테스트: [PaymentGroupIntegrationTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/order/service/PaymentGroupIntegrationTest.java) · [PaymentDeliveryBoundaryPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/PaymentDeliveryBoundaryPostgresTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
