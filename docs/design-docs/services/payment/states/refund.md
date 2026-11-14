# Payment 환불 실행

<!-- visual: relations -->

기준: `cc91c09b`와 #331 종료 보상 변경. 실제 PostgreSQL / Mock gateway 검증 결과이며 외부 PG·운영 실행과 구분한다.

[시각 자료](refund.html)

## 범위와 불변조건

payment.refunds.execution-enabled=false일 때 일반 환불은 양수·0원 모두 접수만 한다. 저장된 주문 종료와 원결제 전액을 대조한 보상만 예약·실행·재시작 스캔에 포함한다. 원결제→환불 실행 순서로 잠그고 PROCESSING/UNKNOWN/SUCCESS 합계로 한도를 검사한다. lease를 잃은 worker의 늦은 결과 쓰기는 허용하지 않는다.

보상 통지가 종료 HTTP보다 먼저 도착하면 503을 반환해 종료 전달 후 같은 요청으로 재시도한다. 다른 종료/환불 ID로 원결제를 중복 보상할 수 없고 동일 키의 변경 원문은 409다. 0원 실행은 PG를 생략하며 Core는 과금 없는 늦은 성공에 보상 환불 자체를 만들지 않는다.

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

RefundMockEngineTest와 PaymentBoundaryPostgresTest에서 일반 환불 비활성 상태의 보상 전용 예약/재시작 복구, 일반 환불 대기, 보상 중복/변경 요청·선도착 재시도를 검증했다. 실제 건수와 환경은 Payment 결제 상태 문서의 검사 결과를 따른다. Core 재고·쿠폰 재복구 금지의 통합 증거는 Core 테스트에서 별도 확인한다.
