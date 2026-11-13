# CommerceCore 주문·결제 묶음

<!-- visual: relations -->

기준: #331 Core V104·Payment V4 구현. 기존 버전0과 신규 주문서 버전1을 구분한다. 실제 검증 결과·전체 회귀 진행 상태는 [워크로그](../../../../worklog/2026-11-13.md)를 따른다.

[시각 자료](order.html)

## 범위와 불변조건

확정 결과 재수신은 동일 결과인지 검사하고 중복 재고 반영을 막는다. 기존 버전0의 PAYMENT_CONFIRMING은 결과 미확정 동안 유지한다. 버전1은 결제 미확정에도 최초10분 만료를 적용한다. OrderStatus 주석에 남은 제안 표현보다 실제 서비스와 조건부 UPDATE를 기준으로 읽었다.

## 기존 버전0 전이

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

테스트 링크는 검증 경로다. 실제 실행 환경·건수·실패·스킵은 워크로그에서 구분한다.

## 신규 주문서 버전1

[Order 모듈 상태도와 물리 매핑](../../../commercecore/order/상태관리도.md)이 상세 원본이다. PENDING API값은 물리 PENDING_PAYMENT이며 생성 때 SKU 스냅샷/선점과 DB시각+10분을 저장한다. 기한 내 SUCCESS는PAID/예약소진, 기한 경과는FAILED+EXPIRED/선점해제/Payment종료 의도다. UNKNOWN으로 연장하지 않는다. 늦은SUCCESS는 전액 보상하고 주문·SKU·Cart·쿠폰 효과를 다시 적용하지 않는다.

수량 환불 성공 시 PARTIALLY_REFUNDED 또는 REFUNDED다. 주문 전체 수량의 누적 성공에만 쿠폰을 원래 유효기간 그대로 한 번 복원한다. 구 API 조회·결제·만료도 policy_version을 분기해 새 주문을15분 규칙으로 처리하지 않는다.
