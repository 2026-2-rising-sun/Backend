# 결제 환불 수용 기준

상태: 진행 중
검증: [결제 재설계 실행 계획](../../../../worklog/plans/active/337-payment-redesign/README.md)

Payment가 승인된 환불과 시스템 보상을 실행하는 기준이다. 환불해도 되는지는 Commerce가 정한다. 근거: [Payment 목표 설계](../design.html) `#f7`, `#f6`, `#contract`, [ADR 0337-3](../../../../adr/0337-3-payment-result-delivery.md). 서비스 사이 흐름은 [환불 흐름](../../../flows/refund.md)에 있다.

요청과 결과는 HTTP가 아니라 Kafka다. Commerce는 고객 환불(`purpose: USER_REFUND`)과 시스템 보상(`SYSTEM_COMPENSATION`)을 모두 `commerce.refund.requests.v1`의 `RefundRequested`로 보낸다([요청 계약](../../../../../contracts/events/commerce-refund-requests.yaml)). Payment는 `payment.refund.results.v1`의 `RefundResultRecorded`로 답한다([결과 계약](../../../../../contracts/events/payment-refund-results.yaml)). 업무 실패는 HTTP 응답 코드 대신 결과 이벤트의 `status: FAILED`와 `failureCode`로 알린다.

결과를 보내기 전에 Core가 그 결과를 받아들일 수 있는지 확인한다. Core 결과 소비자는 원문과 맞지 않는 결과를 받으면 무한 재시도하고, 그동안 파티션이 막힌다(#389). 그래서 결과에는 받은 요청 payload를 고치지 않고 그대로 싣는다.

## 접수

- [x] **REF-ACCEPT-01** 요청을 받으면 요청 payload 원문과 함께 PROCESSING 환불을 저장한다. 실행 ID(`executionId`, UUID)는 이때 정하고 바꾸지 않는다. 토스 호출은 접수 트랜잭션 밖에서 한다. 실행은 목적(`purpose`)별 처리 지점에서 시작한다. 그 목적의 실행이 아직 없으면 접수만 하고 결과를 보내지 않는다.
- [x] **REF-ACCEPT-02** 같은 `refundRequestId`·같은 본문을 다시 받으면 새 환불을 만들지 않는다. 저장된 결과가 있으면 같은 `resultVersion`·같은 payload로 다시 보낸다. 결과가 없으면 아무것도 보내지 않는다. 다른 본문이면 수신을 `REQUEST_CONFLICT`로 기록하고(받은 원문 보존) ERROR 로그를 남긴다. 결과는 보내지 않고, 원래 요청과 결과는 건드리지 않는다. 결과를 보내지 않는 이유는 Core가 원문과 다른 결과를 거부해 파티션이 막히기 때문이다(#389). (EC 39)
- [x] **REF-ACCEPT-03** 같은 결제의 환불 합이 결제 금액을 넘는 요청은 FAILED(`OVER_REFUND`)다. 동시 요청이어도 한 건만 통과한다. 마지막 방어는 DB 제약 `payment.refunded_amount BETWEEN 0 AND amount`다. (EC 40)
- [x] **REF-ACCEPT-04** 실행 전에 결제와 요청을 맞춰 보고, 맞지 않으면 돈을 움직이지 않고 FAILED다. 부분 취소가 안 되는 결제의 부분 환불은 `PARTIAL_NOT_ALLOWED`, 이 결제에 없는 판매자 주문은 `UNKNOWN_SELLER_ORDER`, 환불할 수 없는 결제 상태는 `NOT_REFUNDABLE`이다. (EC 43)
- [x] **REF-ACCEPT-05** 다음 경우는 돈을 움직이지 않고 FAILED로 저장해 보내고, ERROR 로그를 남긴다.
  - `orderRef`로 결제를 찾지 못함: `PAYMENT_NOT_FOUND`
  - `paymentId`가 그 결제와 다름: `PAYMENT_MISMATCH`

  `items`가 없는 요청처럼 계약을 어긴 메시지는 REF-ACCEPT-07을 따른다.
- [x] **REF-ACCEPT-06** 결제당 진행 중(PROCESSING·UNKNOWN)인 `SYSTEM_COMPENSATION`은 1건이다. DB의 부분 UNIQUE로 막는다. 같은 결제에 다른 `refundRequestId`로 보상이 또 오면 FAILED(`COMPENSATION_IN_PROGRESS`)로 저장해 보내고 ERROR 로그를 남긴다. 돈은 움직이지 않는다.
- [x] **REF-ACCEPT-07** 해석할 수 없는 메시지는 `{토픽}.payment.dlt`(`commerce.refund.requests.v1.payment.dlt`)로 보내고, 뒤 메시지를 계속 처리한다. 다음 메시지가 해석할 수 없는 메시지에 해당한다.
  - JSON이 아님
  - 봉투나 키가 계약과 다름
  - `refundRequestId`·`orderRef`·`purpose`·`amount`가 계약과 다름

  일시적인 DB 장애만 offset을 넘기지 않고 계속 재시도한다. 그 밖의 예외는 횟수를 제한해 재시도한 뒤 DLT로 보낸다.

## 실행

- [x] **REF-EXEC-01** 접수 커밋 직후 실행한다. 끝나지 못한 PROCESSING 환불은 스케줄러가 다시 집는다. 두 실행이 겹쳐도 멱등 키(refund_request_id)로 이중 환불이 없다. (EC 41)
- [x] **REF-EXEC-02** 성공하면 한 트랜잭션에 다음을 기록한다.
  - 환불 SUCCEEDED
  - 결제 환불액과 상태(PARTIALLY_REFUNDED/REFUNDED)
  - 분개([LED-REFUND-01](ledger.md))
  - `RefundResultRecorded`(SUCCEEDED, `refundedAmount` = 요청 금액)
- [x] **REF-EXEC-03** 이전 호출 없는 첫 호출을 토스가 거절(4xx)하면 FAILED와 `RefundResultRecorded`(FAILED, `refundedAmount` 0)를 기록한다. (EC 42)
- [x] **REF-EXEC-04** 결과를 모르면 환불을 UNKNOWN으로 둔다. UNKNOWN 결과는 보내지 않는다. 결제 조회로 취소 내역을 확인하고, 없으면 같은 멱등 키로 다시 요청한다. 조회 마감이 지나면 멈추고 대사가 맡는다. 토스가 `ALREADY_REFUNDING_PAYMENT`(앞선 취소 진행 중)로 답하면 FAILED가 아니라 불명이다(#401). 앞선 환불이 UNKNOWN이어도 뒤 환불의 취소는 나가고, 미확정 금액은 한도에 포함한다. 고객 전액 환불에 토스가 PARTIAL_CANCELED로 답해도 바로 확정하거나 보류하지 않고, REF-EXEC-06의 24시간 상한까지 재시도한 뒤 사람 확인으로 넘긴다.
- [x] **REF-EXEC-05** 쿠폰 등으로 실결제액이 0원인 항목의 고객 환불은 정상 요청이다. 전체 `amount`가 0이면 결제 상태만 확인하고(APPROVED·PARTIALLY_REFUNDED·REFUNDED가 아니면 FAILED `NOT_REFUNDABLE`), 토스 호출·분개·결제 변경 없이 SUCCEEDED(`refundedAmount` 0, 거래 키 없음)를 기록한다. 금액이 있는 요청의 0원 판매자 줄은 판매자 주문·누적 한도 검증과 `refund_item`에서 뺀다.
- [x] **REF-EXEC-06** 인프라·PG 장애(429, 401, 설정성 403, 5xx, 타임아웃, 연결 실패)로는 고객 환불을 FAILED로 끝내지 않고 결과도 보내지 않는다. Core 주문은 환불 처리중으로 남는다. FAILED는 돈이 움직이지 않은 것이 확정된 경우, 즉 토스 호출 전 검증 실패와 이전 호출이 없는 첫 호출의 명확한 거절만이다.
  - HTTP 응답을 받은 NotSent는 토스에 도달했으므로 UNKNOWN을 유지해 한도를 잡고, 조회에 취소 내역이 없으면 같은 멱등 키·같은 본문으로 다시 요청한다. 응답이 없는 연결 실패만 PROCESSING으로 돌아갈 수 있다.
  - 첫 시도부터 30분까지는 기존 주기로, 그 뒤 24시간까지는 10분 간격으로 재시도한다. 24시간 상한은 밀리지 않고 다음 처리 시각도 상한을 넘지 않는다. 지나면 UNKNOWN에 `next_attempt_at` NULL로 멈추고 `[사람 확인 필요]` ERROR를 한 번 남긴다. 토스 멱등 키 유효기간(15일)보다 먼저 멈춘다. 상한 뒤 결제 반영이 실패해도 재전송 없이 같은 상태로 멈춘다.
  - 예약과 재요청은 결제 잠금 뒤 이 실행의 선점(`attempt_count`)이 그대로일 때만 한다. 선점을 잃은 실행은 호출 기록도 토스 호출도 남기지 않는다.

## 시스템 보상과 늦은 승인

- [x] **REF-LATE-01** `SYSTEM_COMPENSATION` 요청(사유 `LATE_APPROVAL`·`APPROVAL_UNKNOWN` 등)을 받으면 결제 전액 환불을 접수한다. refundRequestId는 Commerce가 만든 UUID이고, 주문당 보상 1건은 Commerce가 보상 fence로 지킨다. Payment는 같은 ID 중복 수신을 1건으로 처리한다. 토스 취소의 Idempotency-Key는 `cancel-{pg_order_id}`(그 결제의 마지막 승인 시도 결제창 ID)라 승인 응답 불일치 보상과 같은 요청이 된다. (EC 28, 37)
- [x] **REF-LATE-02** 시스템 보상의 FAILED 뒤에 더 높은 `resultVersion`으로 SUCCEEDED·NO_CHARGE를 보낼 수 있다. 결제가 승인되지 않아 돌려줄 돈이 없으면 NO_CHARGE(`failureCode: NO_CHARGE`, `refundedAmount` 0)다. USER_REFUND에는 NO_CHARGE를 쓰지 않는다.
- 시스템 보상의 결제 상태별·토스 조회 결과별 처리는 [#386](https://github.com/2026-2-rising-sun/Backend/issues/386)의 표를 따른다. 표 밖 경우는 다음과 같다.
  - 요청 금액이 결제 금액과 다르면 결제 상태를 보기 전에 토스 호출 없이 FAILED(`PAYMENT_MISMATCH`, `refundedAmount` 0)로 답한다.
  - 결제가 PARTIALLY_REFUNDED·REFUNDED면 토스 호출 없이 FAILED(`NOT_REFUNDABLE`, `refundedAmount` 0)로 답한다. 그 돈은 다른 환불의 결과라 SUCCEEDED로 보내지 않는다. 단, 이 보상의 취소가 토스에 닿았을 수 있으면 환불액 0으로 답하지 않고 보류해 사람이 확인한다.
  - APPROVED 결제는 결제 행 잠금 안에서 상태와 `refunded_amount = 0`을 다시 확인한다. 그 사이 환불이 반영됐으면 전액 취소를 보내지 않고 `NOT_REFUNDABLE`로 답한다. 토스에 갔을 수 있는 다른 환불이 있으면 끝날 때까지 기다리되, 기다리는 시간도 아래 24시간 상한에 포함한다.
  - 토스 장애(429, 401, 설정성 403, 5xx, 타임아웃, 연결 실패)로는 시스템 보상을 FAILED로 끝내지 않고 결과도 보내지 않는다. 토스를 부르기 전에 UNKNOWN으로 한도를 잡고, 이전 취소가 토스에 닿았을 수 있으면(응답을 받은 NotSent 포함) 조회해 취소 내역이 없을 때만 같은 키·같은 본문으로 다시 보낸다.
  - 시스템 보상의 FAILED는 돈이 움직이지 않은 것이 확정된 호출 전 판정(`PAYMENT_MISMATCH`, `NOT_REFUNDABLE`, 접수 단계 코드)뿐이다. 토스 거절로는 FAILED를 보내지 않는다.
  - 재시도는 30초부터 두 배씩 늘려 10분 간격까지 간다. 이 보상의 첫 예약(토스를 부르기 전 UNKNOWN 전환, 고객 환불 대기 포함)부터 24시간(`refundGiveUpAfter`)이 지나면 새 취소를 보내지 않고 마지막 조회 뒤 UNKNOWN + `next_attempt_at` NULL로 멈추며 `[사람 확인 필요]` ERROR를 한 번 남긴다. 첫 예약은 첫 토스 호출보다 이르거나 같아서 취소 키를 쓰는 기간은 24시간 이하이고, 토스 멱등 키 유효기간(15일) 안에 멈춘다. 모든 상태 변경과 취소는 이 실행이 선점한 `attempt_count`를 조건으로 해, 멈췄다 깨어난 실행은 호출 기록·취소·결과를 남기지 못한다.
  - APPROVED 취소가 `ALREADY_CANCELED_PAYMENT`인데 토스 취소 내역에 이 보상의 취소가 없거나, 취소가 `NOT_CANCELABLE_PAYMENT`면 재시도하지 않고 결과를 보류해 사람이 확인한다. SUCCEEDED로 보내지 않는다.

## 운영 관측

- [x] **REF-OBS-01** 기준 시간(12분)을 넘겨 확정되지 않은 건수를 `shoppinglive.payment.unresolved.count`로 낸다. `kind`는 `refund_unknown`(결과를 모르는 고객 환불), `compensation_pending`(PROCESSING·UNKNOWN인 시스템 보상)이다. 24시간 상한으로 `next_attempt_at`이 NULL로 멈춘 건도 사람이 볼 대상이므로 포함하고, 확정되면 빠진다. 지표에 결제키·주문번호·고객 정보를 넣지 않는다. 알림 규칙은 Infra #64가 맡는다(#387).

## 결과 이벤트

- [x] **REF-EVENT-01** `RefundResultRecorded`는 상태 변경과 같은 트랜잭션에 공통 outbox로 기록하고, `payment.refund.results.v1`에 orderRef 키로 보낸다. payload는 받은 요청 payload 그대로에 다음 7개 필드를 더한 것이다.
  - `executionId`
  - `resultVersion`: 요청마다 1부터 시작해 결과를 새로 기록할 때마다 1씩 오른다
  - `status`: SUCCEEDED·FAILED·NO_CHARGE
  - `refundedAmount`
  - `resolvedAt`
  - `nextActionAt`: null
  - `failureCode`

  다시 보낼 때 payload는 처음과 같아야 하므로, 처음 만든 결과 문자열(`resolvedAt` 포함)을 저장해 두고 그대로 보낸다. 계약 YAML에 예시와 발행 표본이 있다.
