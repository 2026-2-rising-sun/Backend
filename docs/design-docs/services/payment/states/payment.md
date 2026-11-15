# Payment 결제 상태

<!-- visual: relations -->

결제 재설계(#337)의 `payment.status`다. 이 문서는 지금 코드에 있는 전이만 적는다. 환불(Phase 3)은 [목표 설계](../design.html#states)에 있다.

기준: `feat/#349-payment-recovery`의 코드(결제 생성·결제창 #348, 승인 처리·승인 API #338, 결과 불명 복구·만료 #349).

[시각 자료](payment.html)

## 범위와 불변조건

상태는 setter로 바꾸지 않고 이전 상태 조건부 UPDATE로만 바꾼다. 0행이면 다시 읽어 판단한다. 토스 호출은 DB 트랜잭션 밖이고, 호출 전에 APPROVING과 호출 기록(pg_call, 결과 UNKNOWN)을 먼저 커밋한다. 토스 결과를 한 번이라도 모르면 CREATED로 되돌리지 않는다. 승인 분개와 `PaymentApproved` outbox는 APPROVED로 바꾸는 UPDATE가 1행일 때만 같은 트랜잭션에서 쓴다. 토스에서 승인됐는데 우리 결제를 APPROVED로 바꿀 수 없으면 CANCELED로 선점한 뒤 토스 응답의 paymentKey로 전액 취소한다. 이미 승인된 결제는 취소하지 않는다. 복구·만료 스케줄러는 분산 락 없이 돌고, 같은 행을 여러 인스턴스가 집어도 조건부 UPDATE라 한 번만 반영된다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | CREATED | 결제 생성. 같은 orderRef는 `ON CONFLICT DO NOTHING` 영향 행 수로 판단한다. 첫 결제창 `{orderRef}-1`. |
| CREATED | CREATED | 결제창 다시 열기. 만료 전일 때만 `pg_order_id`를 다음 회차로 바꾼다. |
| CREATED | APPROVING | 승인 검증 통과. `WHERE status='CREATED' AND pg_order_id·amount·buyer_id 일치 AND expires_at > now()`. 동시 요청은 한 건만 1행이다. 같은 트랜잭션에서 paymentKey 저장, pg_call(CONFIRM, UNKNOWN) 기록. |
| APPROVING | APPROVED | 토스 성공이고 응답(DONE·orderId·totalAmount·paymentKey)이 DB 값과 맞다. 승인 분개(PG 보관금/구매자 예수금)와 `PaymentApproved` outbox를 함께 쓴다. |
| UNKNOWN | APPROVED | 같은 승인 반영 함수(`applyDone`). 불명 뒤 재호출이 성공했거나 조회기가 DONE을 확인했다. 주문이 이미 닫혔어도 같다. 동시에 반영해도 1행만 바뀌고 나머지는 다시 읽어 APPROVED면 끝낸다. |
| APPROVING | CREATED | 첫 호출이 거절이거나 토스에 가지 않았다. `approving_at`과 `pg_order_id`를 비운다. 같은 결제창으로 다시 승인하면 STALE_CHECKOUT이라 새 결제창이 필요하다. |
| APPROVING | UNKNOWN | 첫 호출이 불명이고, 같은 멱등 키로 1번 더 부른 결과도 성공이 아니다. 첫 조회 10초 뒤, 조회 마감은 `payment.recovery.check-deadline`(30분) 뒤. |
| APPROVING | UNKNOWN | 정체 스위퍼. `approving_at`이 3분(`approving-stuck-after`) 넘게 지났다(승인 처리 중 서버 종료 등). 바로 조회(`next_check_at = now()`), 마감 30분 뒤. 바깥 `AND status='APPROVING'`으로 동시 실행에도 한 번만 바뀐다. |
| UNKNOWN | UNKNOWN | 조회기. 조회 차례가 되면 토스를 부르기 전에 다음 조회를 예약해 선점한다(`WHERE status='UNKNOWN' AND next_check_at <= now()`, 0행이면 다른 인스턴스가 집음). 간격은 불명이 된 뒤 지난 시간이 40초 미만 30초, 100초 미만 1분, 이후 5분이고 마감을 넘기지 않는다. 조회 결과가 IN_PROGRESS 등·거절·보내지 않음·불명이면 유지하고 마지막 토스 상태를 `pg_status`에 남긴다. PARTIAL_CANCELED는 사람 확인 ERROR 로그. |
| UNKNOWN | UNKNOWN | 조회 마감이 지났다. `next_check_at = NULL`로 조회 대상에서 빼고(1행일 때만) `payment.recovery.deadline` ERROR 로그를 1번 남긴다. 토스를 부르지 않는다. 이후는 거래 대사가 맡는다. |
| APPROVING | CANCELED | 토스 성공인데 응답이 맞지 않아 보상 취소한다. 토스 취소 전에 CANCELED로 선점하고(조건부 UPDATE 한 번), 취소가 성공이 아니면 CANCELED로 두고 사람 확인용 ERROR 로그를 남긴다(자동 재시도 없음). |
| UNKNOWN | CANCELED | 같은 보상 취소. 조건은 `status IN ('APPROVING','UNKNOWN','CREATED')`. 0행이고 결제가 APPROVED(이후)면 토스 취소를 부르지 않는다. |
| CREATED | CANCELED | 같은 보상 취소(토스는 승인했는데 우리 결제가 이미 CREATED로 돌아간 경우). |
| UNKNOWN | CREATED | 조회 결과 ABORTED·EXPIRED. `approving_at`·`pg_order_id`·`next_check_at`·`check_deadline_at`을 비운다. 새 결제창으로 다시 결제한다. `WHERE status='UNKNOWN'`. |
| UNKNOWN | CANCELED | 조회 결과 CANCELED(전액 취소). 분개 없이 닫고 WARN 로그. `WHERE status='UNKNOWN'`. |
| CREATED | CANCELED | 만료. `expires_at < now()`인 CREATED를 묶음으로 닫는다. 분개·이벤트 없음. 승인 시작(`expires_at > now()`)과 같은 행을 노려도 한쪽만 1행이다. |

## 구현과 검증 근거

- `ApprovalService.java`: 검증·APPROVING 전환(`start`), 토스 호출·재호출(`process`)
- `PaymentOutcomeService.java`: `applyDone`·`applyDeclined`·`applyNotSent`·`applyUnknown`·`compensate`·`applyQuery`(조회 결과)
- `ApprovingSweeper.java`·`UnknownPaymentPoller.java`·`PaymentExpiryJob.java`: 복구·만료 작업. `RecoveryScheduler.java`가 `payment.recovery.enabled`일 때 주기 실행
- `PaymentRepository.java`: 조건부 UPDATE(`startApproval`·`approve`·`revertToCreated`·`markUnknown`·`markCanceled`·`sweepStuckApproving`·`stopChecking`·`claimCheck`·`revertUnknownToCreated`·`cancelUnknown`·`expireCreated`)
- `PaymentService.java`: 생성·결제창

관련 테스트: `ApprovalValidationTest.java`(H2) · `ApprovalOutcomePostgresTest.java` · `ApprovalConcurrencyPostgresTest.java` · `ApprovingSweeperPostgresTest.java` · `UnknownPaymentPollerPostgresTest.java` · `PaymentExpiryPostgresTest.java` · `RecoveryConcurrencyPostgresTest.java` · `PaymentScenarioPostgresTest.java` · `PaymentCreateServiceTest.java` · `PaymentCheckoutServiceTest.java`.

테스트 링크는 검증 경로다. 실제 실행 결과는 워크로그와 PR 본문에 있다.
