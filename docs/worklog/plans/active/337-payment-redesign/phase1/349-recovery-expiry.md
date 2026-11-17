# #349 결과 불명 복구와 만료

상태: 설계 확정(2026-11-15)
이슈: [#349](https://github.com/2026-2-rising-sun/Backend/issues/349) · Phase 문서: [Phase 1 결제 1차](../phase1-payment.md) · 스키마: [#348 설계](348-create-api.md#db-v4__payment_coresql) · 승인 반영: [#338 설계](338-approval-ledger.md#2-승인-처리)

## 개요

끝나면 **결과를 모르는 결제(UNKNOWN)와 승인 중에 멈춘 결제(APPROVING)가 스스로 확정**되고, 만료 시각이 지난 결제는 스스로 닫힌다. 사람이 손대는 경우는 조회 마감(30분)이 지난 결제뿐이고, 그때는 ERROR 로그가 남는다. 승인 반영은 #338의 `applyDone`을 그대로 쓴다.

## 범위

| 포함 | 요구사항 ID |
|---|---|
| APPROVING 정체 → UNKNOWN | PAY-RECOVER-01 |
| UNKNOWN 조회와 확정 | PAY-RECOVER-02–04 |
| CREATED 만료 → CANCELED | PAY-EXPIRE-01 |
| 여러 인스턴스 동시 실행 | PAY-RECOVER-05 |

하지 않는 것: 웹훅, 거래 대사(후순위), 환불 결과 불명(Phase 3), 분산 락.

## 구성 요소

```text
payment/service   ApprovingSweeper      @Scheduled  APPROVING 3분 초과 → UNKNOWN
                  UnknownPaymentPoller  @Scheduled  UNKNOWN 조회 → applyDone / CREATED / 다음 조회
                  PaymentExpiryJob      @Scheduled  CREATED 만료 → CANCELED
```

`@EnableScheduling`은 `payment.recovery.enabled`(기본 `true`)가 켜졌을 때만 등록한다. 통합 테스트는 `false`로 두고 메서드를 직접 부른다.

## 설정 (제안 이름)

`Duration` 설정은 SQL에 초 단위 숫자로 넘기고 `(:seconds * interval '1 second')`로 쓴다. 이 SQL은 PostgreSQL 전용이며 테스트도 `@Tag("postgres")`다.

| 키 | 기본값 | 의미 | 근거 |
|---|---|---|---|
| `payment.recovery.enabled` | `true` | 스케줄러 3개 켜기 | |
| `payment.recovery.sweep-interval` | `30s` | 스위퍼 주기 | |
| `payment.recovery.sweep-batch-size` | `100` | 한 번에 옮길 결제 수 | |
| `payment.recovery.approving-stuck-after` | `3m` | APPROVING 정체 기준 | 토스 호출 최대 60초 × 2보다 길게 |
| `payment.recovery.poll-interval` | `5s` | 조회기 주기 | 첫 조회 10초를 지키려면 더 짧아야 함 |
| `payment.recovery.poll-batch-size` | `50` | 한 번에 조회할 결제 수 | |
| `payment.recovery.check-deadline` | `30m` | 조회 마감 | decision.md 제안값 |
| `payment.expiry.interval` | `1m` | 만료 주기 | |
| `payment.expiry.batch-size` | `100` | 한 번에 닫을 결제 수 | |

## 1. APPROVING 정체 스위퍼 (PAY-RECOVER-01)

```sql
UPDATE payment
   SET status = 'UNKNOWN', next_check_at = now(),
       check_deadline_at = now() + (:checkDeadlineSeconds * interval '1 second'), updated_at = now()
 WHERE id IN (SELECT id FROM payment
               WHERE status = 'APPROVING' AND approving_at < now() - (:stuckAfterSeconds * interval '1 second')
               ORDER BY approving_at LIMIT :sweepBatchSize)
   AND status = 'APPROVING';
```

- 승인 처리 중에 서버가 죽으면 결제가 APPROVING으로 남는다. 저장된 `pg_payment_key`로 조회할 수 있게 UNKNOWN으로 옮긴다(EC 22, 23).
- 바깥 `AND status = 'APPROVING'`이 동시 실행을 막는다. 두 인스턴스가 같이 돌아도 결과는 같다.

## 2. UNKNOWN 조회기 (PAY-RECOVER-02–04)

**고르기**(짧은 읽기 트랜잭션):

```sql
SELECT id FROM payment
 WHERE status = 'UNKNOWN' AND next_check_at <= now()
 ORDER BY next_check_at LIMIT :batchSize;
```

**결제마다**(트랜잭션 밖에서 토스 호출):

| 순서 | 할 일 |
|---|---|
| 1 | 결제를 다시 읽는다. UNKNOWN이 아니면 건너뛴다(다른 인스턴스가 먼저 처리함) |
| 2 | `check_deadline_at` < now()면 **조회를 멈춘다**: `next_check_at = NULL`(조회 대상에서 빠짐) + ERROR 로그 1번. 끝 (PAY-RECOVER-04) |
| 3 | pg_call(kind=QUERY, outcome=UNKNOWN) INSERT, 커밋 |
| 4 | 토스 `getPayment(pg_payment_key)` — 트랜잭션 밖 |
| 5 | 결과에 따라 아래 표, 한 트랜잭션 |

| 조회 결과 | 처리 | 근거 |
|---|---|---|
| `Success`, `status = DONE`, 응답 검증 통과 | **#338 `applyDone`** → APPROVED + 분개 + `PaymentApproved`. 주문이 이미 닫혔어도 같다(판단은 Commerce) | PAY-RECOVER-02, EC 37 |
| `Success`, `status = DONE`, 응답 검증 실패 | #338 보상 취소와 같은 경로 → CANCELED + ERROR 로그 | PAY-CONFIRM-07 |
| `Success`, `status ∈ {ABORTED, EXPIRED}` | `CREATED`, `approving_at = NULL`, `pg_order_id = NULL`(#338과 같은 이유), `next_check_at = NULL`, `check_deadline_at = NULL` · `WHERE status = 'UNKNOWN'` | PAY-RECOVER-03(요구사항이 ABORTED·EXPIRED 둘 다 적는다. 인증 뒤 승인되지 않은 결제는 토스에서 EXPIRED가 된다) |
| `Success`, 그 밖의 상태(`IN_PROGRESS`, `READY`, `WAITING_FOR_DEPOSIT` 등) | UNKNOWN 유지, 다음 조회 예약 | PAY-RECOVER-03 |
| `Success`, `status = CANCELED`(전액 취소) | `CANCELED` · `WHERE status = 'UNKNOWN'` + WARN 로그. 분개는 쓰지 않는다(UNKNOWN은 분개가 없다) | 돈이 모두 돌아갔다. 토스 관리자 화면에서 직접 취소했거나, 우리 보상 취소 응답을 잃은 경우 |
| `Success`, `status = PARTIAL_CANCELED` | UNKNOWN 유지 + ERROR 로그. 조회 마감까지 같은 처리(사람이 확인) | 일부 돈이 남아 원장 판단이 필요하다 |
| `Declined` · `NotSent` · `Unknown` | UNKNOWN 유지, 다음 조회 예약. pg_call에 결과 기록 | |

- 응답 검증은 승인과 같다: `orderId = pg_order_id`, `totalAmount = amount`, `paymentKey = pg_payment_key`.
- 모든 상태 변경은 `WHERE status = 'UNKNOWN'` 조건부 UPDATE다. 0행이면 다른 인스턴스가 먼저 반영한 것이므로 아무것도 쓰지 않는다.

**다음 조회 예약**. 결과 불명이 된 시각 `t0 = check_deadline_at − 30분`에서 지난 시간으로 간격을 고른다. 시도 횟수 컬럼을 따로 두지 않는다.

| `now − t0` | 다음 조회 | 요구사항의 간격 |
|---|---|---|
| (UNKNOWN이 될 때) | `t0 + 10초` | 10초 |
| 40초 미만 | `now + 30초` | 30초 |
| 100초 미만 | `now + 1분` | 1분 |
| 그 이상 | `now + 5분` | 5분, 마감까지 반복 |

```sql
UPDATE payment SET next_check_at = LEAST(:next, check_deadline_at), updated_at = now()
 WHERE id = :id AND status = 'UNKNOWN';
```

다음 조회는 마감을 넘기지 않는다(마감 로그가 늦게 찍히지 않게). 스위퍼가 UNKNOWN으로 옮긴 결제는 `next_check_at = now()`로 바로 조회한다. 이미 3분 넘게 멈춘 결제라 10초를 더 기다릴 이유가 없다(PAY-CONFIRM-10의 10초는 승인 직후 UNKNOWN에만 적용).

**마감까지 IN_PROGRESS로 남는 경우**: ①을 커밋하고 토스를 부르기 전에 서버가 죽으면 토스 상태는 IN_PROGRESS로 남는다. 인증 유효 시간이 지나면 토스가 EXPIRED로 바꾸므로 보통 조회 중에 CREATED로 돌아간다. 마감(30분)까지 바뀌지 않으면 UNKNOWN에 그대로 두고 ERROR 로그로 사람이 확인한다. 자동으로 CREATED로 돌리지 않는다(돈이 나갔는지 모른다).

**조회 마감 로그 형식**(ERROR, 한 줄):

```text
payment.recovery.deadline paymentId=1001 orderRef=OD-20261110-K7Q2M9XA pgOrderId=OD-20261110-K7Q2M9XA-1 lastPgStatus=IN_PROGRESS
```

`paymentKey`와 시크릿 키는 로그에 남기지 않는다.

## 3. 만료 (PAY-EXPIRE-01)

```sql
UPDATE payment SET status = 'CANCELED', updated_at = now()
 WHERE id IN (SELECT id FROM payment
               WHERE status = 'CREATED' AND expires_at < now()
               ORDER BY expires_at LIMIT :batchSize)
   AND status = 'CREATED' AND expires_at < now();
```

- 승인 시작 UPDATE는 `status = 'CREATED' AND expires_at > now()`를 조건으로 한다. 두 UPDATE가 같은 행을 노리면 하나만 1행이 된다(EC 31, 35).
- 돈이 나가지 않은 결제라서 분개·이벤트가 없다. Commerce는 자기 만료 배치로 주문을 닫는다(서로 이벤트 없음).

## 여러 인스턴스 (PAY-RECOVER-05)

분산 락을 두지 않는다. 겹쳐 돌 때 일어나는 일:

| 겹침 | 결과 |
|---|---|
| 스위퍼 2개 | 조건부 UPDATE라 한 번만 바뀜 |
| 조회기 2개가 같은 결제 | 토스 조회가 1번 더 갈 수 있다(감수). 반영은 `WHERE status = 'UNKNOWN'`과 원장 UNIQUE로 1번 |
| 조회기 DONE과 승인 ③ 반영이 동시 | 둘 다 `applyDone`이고 `status IN ('APPROVING','UNKNOWN')` 조건이라 한쪽만 1행 |
| 만료와 승인 시작 | 위 "만료" 참고 |

## 테스트

| ID | 테스트(제안 이름) | 종류 |
|---|---|---|
| PAY-RECOVER-01 | `ApprovingSweeperPostgresTest`(3분 경계 앞뒤) | `@Tag("postgres")` |
| PAY-RECOVER-02 | `UnknownPaymentPollerTest`: DONE → APPROVED·분개 1·outbox 1, 주문 닫힘 여부와 무관 | `@Tag("postgres")` |
| PAY-RECOVER-03 | 같은 클래스: ABORTED·EXPIRED → CREATED, IN_PROGRESS → 간격표대로 `next_check_at` | `@Tag("postgres")` |
| PAY-RECOVER-04 | 같은 클래스: 마감 지남 → `next_check_at NULL` + ERROR 로그 1번, 토스 호출 0번 | `@Tag("postgres")` |
| PAY-RECOVER-05 | `RecoveryConcurrencyPostgresTest`: 조회기 2개 동시 → 상태 변경·분개·이벤트 각 1번 | `@Tag("postgres")` |
| PAY-EXPIRE-01 | `PaymentExpiryPostgresTest`: 만료 → CANCELED, 승인 시작과 동시에 → 한쪽만 | `@Tag("postgres")` |

토스는 `MockRestServiceServer` 또는 테스트 대역. 시간은 DB `now()` 기준이라 테스트는 `expires_at`·`approving_at`·`check_deadline_at`을 과거 값으로 넣어 만든다.

## 바뀌는 점

| 구분 | 유지 | 바뀜·새로 생김 | 없어짐 |
|---|---|---|---|
| 동작 | 상태 전이 규칙 | 스케줄러 3개 | 기존 `PaymentDelayReconciler`·`RefundReconciler`(#347에서 삭제) |
| 설정 | — | `payment.recovery.*`, `payment.expiry.*` | 기존 Mock 재시도 정책 |
| 운영 | — | 조회 마감 ERROR 로그를 사람이 확인 | — |
| 문서 | — | `states/payment.md`·`.html`에 정체·조회·만료 전이 | — |

## PR 나누기

| PR | 범위 | base | 브랜치 | 갱신할 문서 |
|---|---|---|---|---|
| PR7 복구 · 만료 | 스케줄러 3개, 설정, 테스트 | #338 PR6 | `feat/#349-payment-recovery` | `states/payment.md`·`.html` |

PR7이 #349를 `Closes`한다. 이 PR 뒤에 Phase 1 종료 검증을 한다.

## 미결정

결정(2026-11-15, 사용자 동의):

1. 조회 간격은 "불명이 된 뒤 지난 시간"으로 고른다. 시도 횟수 컬럼은 두지 않는다.
2. 토스 조회가 `CANCELED`(전액)면 CANCELED로 닫는다. `PARTIAL_CANCELED`는 사람이 확인한다.

## 참고

[결제 요구사항](../../../../../design-docs/services/payment/requirements/payment.md) · [ADR 0337-5](../../../../../adr/0337-5-pg-timeout-and-unknown.md) · 목표 설계 [`#f4`](../../../../../design-docs/services/payment/design.html#f4)·[`#f6`](../../../../../design-docs/services/payment/design.html#f6)·[`#states`](../../../../../design-docs/services/payment/design.html#states) · [decision.md](../decision.md)
