# Payment 결제 실행

<!-- visual: relations -->

기준: `cc91c09b`와 #331 종료/보상 변경. 실제 PostgreSQL / Mock gateway로 생성·종료/호출 경합을 검증했다. 실제 외부 PG 실행 증거는 아니다.

[시각 자료](payment.html)

## 범위와 불변조건

PENDING은 미사용 호환 값, TIMEOUT은 과거 호환 terminal이다. 현재 complete는 SUCCESS·FAILED만 허용한다. lease는 DB 시각·토큰·만료를 마지막 쓰기에서도 검사한다. 재시도 횟수는 첫 호출과 이후 재호출을 구분하며 원본 ID·금액이 다른 재전달은 충돌이다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | requestId·paymentId 유일키와 원문 대조 후 실행 원본을 저장한다. |
| PROCESSING | SUCCESS | 0원은 PG 호출 없이 성공·결과 Outbox를 저장한다. 양수는 유효 lease에서 확정 결과를 반영한다. |
| PROCESSING | UNKNOWN | 제공자 응답 유실·미확정 또는 복구 예외. 실행 시작 여부와 다음 조회 시각을 보존한다. |
| UNKNOWN | UNKNOWN | 재시도 예산 소진 후에도 결과 조회를 계속한다. 시간 경과만으로 실패 확정하지 않는다. |
| UNKNOWN | PROCESSING | 실제 첫 호출이 아직 없었던 lease가 beginInvocation을 수행하는 경우만 PROCESSING으로 기록한다. |
| UNKNOWN | SUCCESS | 제공자 결과 조회 또는 같은 실행의 재호출에서 성공을 확인하고 Outbox와 함께 확정한다. |
| PROCESSING | FAILED | 확정 실패 결과를 유효한 lease에서 반영한다. |
| UNKNOWN | FAILED | 제공자 결과가 실패로 확정된 경우만 반영한다. |
| 신규/미호출 PROCESSING | FAILED | 같은 영속 제어 키에 종료가 먼저 기록되면 PG 호출 없이 실패와 Outbox를 저장한다. |
| 호출 중/UNKNOWN | SUCCESS | 종료 뒤에도 기존 실행의 실제 성공을 보존한다. Core는 실패 주문을 부활시키지 않고 별도 보상한다. |

종료 뒤 이미 호출한 UNKNOWN은 새 승인을 요청하지 않고 결과 조회만 계속한다. 종료 수신은 확정 실패 근거가 아니며 호출 시작 전만 차단한다. 기존 nullable paymentGroupId 단건 흐름은 보존하고 종료 통지는 신규 묶음 주문을 대상으로 한다.

## 구현과 검증 근거

- [PaymentService.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/PaymentService.java)
- [PaymentRecoveryStore.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/PaymentRecoveryStore.java)
- [PaymentAttempt.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentAttempt.java)
- [PaymentStatus.java](../../../../../services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentStatus.java)

관련 기존 테스트: [PaymentBoundaryPostgresTest.java](../../../../../services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentBoundaryPostgresTest.java) · [PaymentRecoveryStorePostgresTest.java](../../../../../services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentRecoveryStorePostgresTest.java).

PaymentBoundaryPostgresTest의 생성/종료·beginInvocation 직전/직후·authorize 진행 중 동기화 경합과 attempt PK가 paymentId와 다른 표본을 실행했다. Payment 단위 8건, 기본 통합 3건, PostgreSQL 41건, migration 6건이 실패·오류·스킵 0이었다. Core 연계 결과 적용과 실제 PG 검증은 별도 범위다.
