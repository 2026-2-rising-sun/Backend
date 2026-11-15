# Payment 결제 상태

<!-- visual: relations -->

결제 재설계(#337)의 `payment.status`다. 이 문서는 지금 코드에 있는 전이만 적는다. 결과 불명 조회·APPROVING 정체 정리·만료(#349)와 환불(Phase 3)은 [목표 설계](../design.html#states)에 있다.

기준: `feat/#338-payment-approval`의 코드(결제 생성·결제창 #348, 승인 처리 #338). 승인 HTTP API와 8초 대기는 아직 없다.

[시각 자료](payment.html)

## 범위와 불변조건

상태는 setter로 바꾸지 않고 이전 상태 조건부 UPDATE로만 바꾼다. 0행이면 다시 읽어 판단한다. 토스 호출은 DB 트랜잭션 밖이고, 호출 전에 APPROVING과 호출 기록(pg_call, 결과 UNKNOWN)을 먼저 커밋한다. 토스 결과를 한 번이라도 모르면 CREATED로 되돌리지 않는다. 승인 분개와 `PaymentApproved` outbox는 APPROVED로 바꾸는 UPDATE가 1행일 때만 같은 트랜잭션에서 쓴다. 토스에서 승인됐는데 우리 결제를 APPROVED로 바꿀 수 없으면 토스 응답의 paymentKey로 전액 취소한다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | CREATED | 결제 생성. 같은 orderRef는 `ON CONFLICT DO NOTHING` 영향 행 수로 판단한다. 첫 결제창 `{orderRef}-1`. |
| CREATED | CREATED | 결제창 다시 열기. 만료 전일 때만 `pg_order_id`를 다음 회차로 바꾼다. |
| CREATED | APPROVING | 승인 검증 통과. `WHERE status='CREATED' AND pg_order_id·amount·buyer_id 일치 AND expires_at > now()`. 동시 요청은 한 건만 1행이다. 같은 트랜잭션에서 paymentKey 저장, pg_call(CONFIRM, UNKNOWN) 기록. |
| APPROVING | APPROVED | 토스 성공이고 응답(DONE·orderId·totalAmount·paymentKey)이 DB 값과 맞다. 승인 분개(PG 보관금/구매자 예수금)와 `PaymentApproved` outbox를 함께 쓴다. |
| UNKNOWN | APPROVED | 같은 승인 반영 함수(`applyDone`). 불명 뒤 재호출이 성공했거나 #349 조회가 DONE을 확인했다. 동시에 반영해도 1행만 바뀌고 나머지는 다시 읽어 APPROVED면 끝낸다. |
| APPROVING | CREATED | 첫 호출이 거절이거나 토스에 가지 않았다. `approving_at`과 `pg_order_id`를 비운다. 같은 결제창으로 다시 승인하면 STALE_CHECKOUT이라 새 결제창이 필요하다. |
| APPROVING | UNKNOWN | 첫 호출이 불명이고, 같은 멱등 키로 1번 더 부른 결과도 성공이 아니다. 첫 조회 10초 뒤, 조회 마감 30분 뒤. |
| APPROVING | CANCELED | 토스 성공인데 응답이 맞지 않아 보상 취소했다. 취소 결과가 불명이어도 CANCELED로 두고 ERROR 로그를 남긴다. |
| UNKNOWN | CANCELED | 같은 보상 취소. 조건은 `status IN ('APPROVING','UNKNOWN','CREATED')`. 0행이면 상태를 두고 ERROR 로그. |
| CREATED | CANCELED | 같은 보상 취소(토스는 승인했는데 우리 결제가 이미 CREATED로 돌아간 경우). |

## 구현과 검증 근거

- `ApprovalService.java`: 검증·APPROVING 전환(`start`), 토스 호출·재호출(`process`)
- `PaymentOutcomeService.java`: `applyDone`·`applyDeclined`·`applyNotSent`·`applyUnknown`·`compensate`
- `PaymentRepository.java`: 조건부 UPDATE(`startApproval`·`approve`·`revertToCreated`·`markUnknown`·`markCanceled`)
- `PaymentService.java`: 생성·결제창

관련 테스트: `ApprovalValidationTest.java`(H2) · `ApprovalOutcomePostgresTest.java` · `ApprovalConcurrencyPostgresTest.java` · `PaymentCreateServiceTest.java` · `PaymentCheckoutServiceTest.java`.

테스트 링크는 검증 경로다. 실제 실행 결과는 워크로그와 PR 본문에 있다.
