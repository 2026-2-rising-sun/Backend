# CommerceCore 환불 접수·결과 반영

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](refund.html)

## 범위와 불변조건

commercecore.refunds.execution-enabled가 꺼져 있으면 REFUND 전달을 하지 않는다. 공개 API 사용 가능 여부와 실행 토글은 계약·설정을 따로 확인한다. 중복 결과와 누적 환불 금액을 확인하며 일부 환불만으로 결제 묶음 전체를 환불 완료로 만들지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 성공한 원결제·소유자·기간·대상·누적 금액·멱등 원문을 검사하고 refund_request와 대상을 영속화한다. |
| PROCESSING | UNKNOWN | Payment의 유효한 미확정 응답을 전달 기록과 환불 요청에 반영한다. |
| UNKNOWN | PROCESSING | Payment의 현재 미확정 스냅샷에 따라 다시 PROCESSING을 기록할 수 있다. |
| PROCESSING | SUCCESS | 원문·금액·대상 검증 후 주문 PAID→REFUNDED, 재고 복원을 같은 트랜잭션에서 처리한다. |
| UNKNOWN | SUCCESS | 조회·이벤트로 확정된 성공 결과를 같은 결과 처리 경로로 반영한다. |
| PROCESSING | FAILED | 확정 실패를 기록하며 성공 환불의 재고 복원을 수행하지 않는다. |
| UNKNOWN | FAILED | 실행 결과가 실패로 확정된 경우에만 변경한다. |

## 구현과 검증 근거

- [RefundIntakeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundIntakeService.java)
- [RefundOutcomeService.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/RefundOutcomeService.java)
- [PaymentResultHandler.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentResultHandler.java)
- [PaymentDispatcher.java](../../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/payment/service/PaymentDispatcher.java)

관련 기존 테스트: [RefundDispatchDisabledPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/RefundDispatchDisabledPostgresTest.java) · [RefundExecutionPostgresTest.java](../../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/payment/service/RefundExecutionPostgresTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
