# CommerceCore 환불 접수·결과 반영

<!-- visual: relations -->

기준: #331 Core V104·Payment V4 구현. 기존 저장 행의 버전0, 신규 주문서 버전1, 신규 구 API 주문 버전2를 구분한다. 버전1/2는 모두 최초10분이며 기존 행의 저장기한은 바꾸지 않는다. 실제 검증 결과·전체 회귀 진행 상태는 [워크로그](../../../../worklog/2026-11-13.md)를 따른다.

[시각 자료](refund.html)

## 범위와 불변조건

commercecore.refunds.execution-enabled가 꺼져 있으면 일반 USER 환불을 전달하지 않는다. 저장된 종료와 대조된 COMPENSATION만 예외로 전달·실행·복구한다. 공개 API 사용 가능 여부와 실행 토글은 계약·설정을 따로 확인한다. 중복 결과와 누적 환불 금액을 확인하며 일부 환불만으로 결제 묶음 전체를 환불 완료로 만들지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 성공한 원결제·소유자·기간·대상·누적 금액·멱등 원문을 검사하고 refund_request와 대상을 영속화한다. |
| PROCESSING | UNKNOWN | Payment의 유효한 미확정 응답을 전달 기록과 환불 요청에 반영한다. |
| UNKNOWN | PROCESSING | Payment의 현재 미확정 스냅샷에 따라 다시 PROCESSING을 기록할 수 있다. |
| PROCESSING | SUCCESS | 원문·금액·대상 검증 후 구 API 버전0/2는 주문 PAID→REFUNDED, 버전1은 성공 수량에 따른 PARTIALLY_REFUNDED/REFUNDED와 재고 복원을 같은 트랜잭션에서 처리한다. |
| UNKNOWN | SUCCESS | 조회·이벤트로 확정된 성공 결과를 같은 결과 처리 경로로 반영한다. |
| PROCESSING | FAILED | 확정 실패를 기록하며 성공 환불의 재고 복원을 수행하지 않는다. |
| UNKNOWN | FAILED | 실행 결과가 실패로 확정된 경우에만 변경한다. |

## 구현과 검증 근거

- [RefundIntakeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundIntakeService.java)
- [RefundOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundOutcomeService.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)

관련 기존 테스트: [RefundDispatchDisabledPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/RefundDispatchDisabledPostgresTest.java) · [RefundExecutionPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/RefundExecutionPostgresTest.java).

테스트 링크는 검증 경로다. 실제 실행 환경·건수·실패·스킵은 워크로그에서 구분한다.

## 신규 수량 환불

[Order 상세 환불 정책](../../../commercecore/order/설계.md#6-수량-환불과-쿠폰-복원)을 따른다. 항목 스냅샷 단가×이번 수량을 남은 항목 실결제액으로 제한한다.4000×3/쿠폰2000은4000/4000/2000이다.0원 잔여 수량도 성공 기록과 SKU 복구에 반영하되 실제PG호출은 없다. 주문별 미확정 USER 한 건을 root잠금으로 직렬화하고 실패 새 키 재접수를 허용한다.

누적 전체 주문 수량 성공 때만 쿠폰 출처를 대조해 한 번 복원한다. 원래 유효기간은 유지한다. COMPENSATION은 사용자의 환불 이력과 구분하고 재고·Cart·쿠폰을 다시 변경하지 않는다.
