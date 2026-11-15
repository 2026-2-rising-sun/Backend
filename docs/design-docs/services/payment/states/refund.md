# Payment 환불 실행

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](refund.html)

## 범위와 불변조건

payment.refunds.execution-enabled=false일 때 양수·0원 모두 접수만 하고 자동 실행·확정을 하지 않는다. 원결제→환불 실행 순서로 잠그고 PROCESSING/UNKNOWN/SUCCESS 합계로 한도를 검사한다. lease를 잃은 worker의 늦은 결과 쓰기는 허용하지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 동일 requestId·coreRefundRequestId 원문 확인과 성공한 원결제·누적 환불 한도 검증 후 접수한다. |
| PROCESSING | UNKNOWN | 제공자 결과 유실·미확정. 다음 조회·lease 상태를 기록한다. |
| UNKNOWN | UNKNOWN | 재호출 예산이 끝나도 결과 조회를 계속한다. |
| PROCESSING | SUCCESS | 실행 활성 상태의 0원은 제공자만 생략한다. 유효 lease·원결제 잠금 검증 후 결과 Outbox와 함께 확정한다. |
| UNKNOWN | SUCCESS | 영속된 제공자 결과를 조회해 확정한다. |
| PROCESSING | FAILED | 제공자 확정 실패를 반영한다. |
| UNKNOWN | FAILED | 조회로 확정된 실패를 반영한다. |

## 구현과 검증 근거

- [RefundExecutionService.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundExecutionService.java)
- [RefundRecoveryStore.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundRecoveryStore.java)
- [RefundOutcomeService.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundOutcomeService.java)

관련 기존 테스트: [RefundRecoveryStorePostgresTest.java](../../../../../services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/RefundRecoveryStorePostgresTest.java) · [RefundMockEngineTest.java](../../../../../services/payment-service/src/test/java/com/shoppinglive/payment/service/RefundMockEngineTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
