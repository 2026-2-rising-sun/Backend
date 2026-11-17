# Payment

책임: 결제·환불 실행, PG(토스) 연동, 원장, 결과 Outbox.

결제 재설계(#337)가 진행 중이다. **새 설계**가 목표이고, **현재 코드** 문서는 재설계 PR에서 코드와 함께 교체한다.

## 새 설계 (결제 재설계)

| 보고 싶은 것 | 문서 |
|---|---|
| 큰 그림·진행 상황 | [실행 계획](../../../worklog/plans/active/337-payment-redesign/README.md) · [#337](https://github.com/2026-2-rising-sun/Backend/issues/337) |
| 수용 기준(ID 원본) | [결제](requirements/payment.md) · [원장](requirements/ledger.md) · [환불](requirements/refund.md) |
| 결정 | ADR [0337-1](../../../adr/0337-1-commerce-relay-internal-api.md) · [0337-2](../../../adr/0337-2-payment-replacement.md) · [0337-3](../../../adr/0337-3-payment-result-delivery.md) · [0337-4](../../../adr/0337-4-ledger-double-entry.md) · [0337-5](../../../adr/0337-5-pg-timeout-and-unknown.md) |
| 그림으로 보기 | [목표 설계](design.html) |
| 결제 1차 상세 | [결제 1차 계획](../../../worklog/plans/active/337-payment-redesign/phase1-payment.md) · [작은 판단](../../../worklog/plans/active/337-payment-redesign/decision.md) |
| 에이전트 주의점 | [Payment 작업 주의점](../../../../services/payment-service/AGENTS.md) |

## 현재 코드 (Mock, 교체 예정)

공개 브라우저 API는 Core가 소유한다. 내부 ServiceToken 계약을 사용한다. 실제 PG 운영 연동은 이번 문서의 증거가 아니다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/payment-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [결제 실행](overview.md) · [시각 자료](overview.html)
- [환불 실행](overview.md) · [시각 자료](overview.html)
- 서비스 간 흐름: [주문·결제](../../flows/order-payment.md) · [환불](../../flows/refund.md)

## 원본·갱신

[구현](../../../../services/payment-service/src/main/java) · [마이그레이션](../../../../services/payment-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.

## Payment 결제 실행

<!-- visual: relations -->

**교체 예정:** 현재 Mock 구현 설명이다. 결제 재설계(#337)에서 교체하며, 새 설계는 [Payment 도메인 홈](overview.md)에 있다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

PENDING은 미사용 호환 값, TIMEOUT은 과거 호환 terminal이다. 현재 complete는 SUCCESS·FAILED만 허용한다. lease는 DB 시각·토큰·만료를 마지막 쓰기에서도 검사한다. 재시도 횟수는 첫 호출과 이후 재호출을 구분하며 원본 ID·금액이 다른 재전달은 충돌이다.

### 전이

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

### 구현과 검증 근거

- [PaymentService.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/PaymentService.java)
- [PaymentRecoveryStore.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/PaymentRecoveryStore.java)
- [PaymentAttempt.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentAttempt.java)
- [PaymentStatus.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentStatus.java)

관련 기존 테스트: [PaymentBoundaryPostgresTest.java](../../../../services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentBoundaryPostgresTest.java) · [PaymentRecoveryStorePostgresTest.java](../../../../services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/PaymentRecoveryStorePostgresTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

## Payment 환불 실행

<!-- visual: relations -->

**교체 예정:** 현재 Mock 구현 설명이다. 결제 재설계(#337)에서 교체하며, 새 설계는 [Payment 도메인 홈](overview.md)에 있다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

payment.refunds.execution-enabled=false일 때 양수·0원 모두 접수만 하고 자동 실행·확정을 하지 않는다. 원결제→환불 실행 순서로 잠그고 PROCESSING/UNKNOWN/SUCCESS 합계로 한도를 검사한다. lease를 잃은 worker의 늦은 결과 쓰기는 허용하지 않는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PROCESSING | 동일 requestId·coreRefundRequestId 원문 확인과 성공한 원결제·누적 환불 한도 검증 후 접수한다. |
| PROCESSING | UNKNOWN | 제공자 결과 유실·미확정. 다음 조회·lease 상태를 기록한다. |
| UNKNOWN | UNKNOWN | 재호출 예산이 끝나도 결과 조회를 계속한다. |
| PROCESSING | SUCCESS | 실행 활성 상태의 0원은 제공자만 생략한다. 유효 lease·원결제 잠금 검증 후 결과 Outbox와 함께 확정한다. |
| UNKNOWN | SUCCESS | 영속된 제공자 결과를 조회해 확정한다. |
| PROCESSING | FAILED | 제공자 확정 실패를 반영한다. |
| UNKNOWN | FAILED | 조회로 확정된 실패를 반영한다. |

### 구현과 검증 근거

- [RefundExecutionService.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundExecutionService.java)
- [RefundRecoveryStore.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundRecoveryStore.java)
- [RefundOutcomeService.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/service/RefundOutcomeService.java)

관련 기존 테스트: [RefundRecoveryStorePostgresTest.java](../../../../services/payment-service/src/integrationTest/java/com/shoppinglive/payment/service/RefundRecoveryStorePostgresTest.java) · [RefundMockEngineTest.java](../../../../services/payment-service/src/test/java/com/shoppinglive/payment/service/RefundMockEngineTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
