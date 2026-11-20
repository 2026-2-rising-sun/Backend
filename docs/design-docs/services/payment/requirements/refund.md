# 결제 환불 수용 기준

상태: 진행 중
검증: [결제 재설계 실행 계획](../../../../worklog/plans/active/337-payment-redesign/README.md)

Payment가 승인된 환불과 시스템 보상을 실행하는 기준이다. 환불해도 되는지는 Commerce가 정한다. 근거: [Payment 목표 설계](../design.html) `#f7`, `#f6`, `#contract`, [ADR 0337-3](../../../../adr/0337-3-payment-result-delivery.md). 서비스 사이 흐름은 [환불 흐름](../../../flows/refund.md)에 있다.

요청과 결과는 HTTP가 아니라 Kafka다. Commerce는 고객 환불(`purpose: USER_REFUND`)과 시스템 보상(`SYSTEM_COMPENSATION`)을 모두 `commerce.refund.requests.v1`의 `RefundRequested`로 보낸다([요청 계약](../../../../../contracts/events/commerce-refund-requests.yaml)). Payment는 `payment.refund.results.v1`의 `RefundResultRecorded`로 답한다([결과 계약](../../../../../contracts/events/payment-refund-results.yaml)). 업무 실패는 HTTP 응답 코드 대신 결과 이벤트의 `status: FAILED`와 `failureCode`로 알린다.

결과를 보내기 전에 Core가 그 결과를 받아들일 수 있는지 확인한다. Core 결과 소비자는 원문과 맞지 않는 결과를 받으면 무한 재시도하고, 그동안 파티션이 막힌다(#389). 그래서 결과에는 받은 요청 payload를 고치지 않고 그대로 싣는다.

## 접수

- [ ] **REF-ACCEPT-01** 요청을 받으면 요청 payload 원문과 함께 PROCESSING 환불을 저장한다. 실행 ID(`executionId`, UUID)는 이때 정하고 바꾸지 않는다. 토스 호출은 접수 트랜잭션 밖에서 한다. 실행은 목적(`purpose`)별 처리 지점에서 시작한다. 그 목적의 실행이 아직 없으면 접수만 하고 결과를 보내지 않는다.
- [x] **REF-ACCEPT-02** 같은 `refundRequestId`·같은 본문을 다시 받으면 새 환불을 만들지 않는다. 저장된 결과가 있으면 같은 `resultVersion`·같은 payload로 다시 보낸다. 결과가 없으면 아무것도 보내지 않는다. 다른 본문이면 수신을 `REQUEST_CONFLICT`로 기록하고(받은 원문 보존) ERROR 로그를 남긴다. 결과는 보내지 않고, 원래 요청과 결과는 건드리지 않는다. 결과를 보내지 않는 이유는 Core가 원문과 다른 결과를 거부해 파티션이 막히기 때문이다(#389). (EC 39)
- [ ] **REF-ACCEPT-03** 같은 결제의 환불 합이 결제 금액을 넘는 요청은 FAILED(`OVER_REFUND`)다. 동시 요청이어도 한 건만 통과한다. 마지막 방어는 DB 제약 `payment.refunded_amount BETWEEN 0 AND amount`다. (EC 40)
- [ ] **REF-ACCEPT-04** 실행 전에 결제와 요청을 맞춰 보고, 맞지 않으면 돈을 움직이지 않고 FAILED다. 부분 취소가 안 되는 결제의 부분 환불은 `PARTIAL_NOT_ALLOWED`, 이 결제에 없는 판매자 주문은 `UNKNOWN_SELLER_ORDER`, 환불할 수 없는 결제 상태는 `NOT_REFUNDABLE`이다. (EC 43)
- [x] **REF-ACCEPT-05** 다음 경우는 돈을 움직이지 않고 FAILED로 저장해 보내고, ERROR 로그를 남긴다.
  - `orderRef`로 결제를 찾지 못함: `PAYMENT_NOT_FOUND`
  - 요청에 `paymentId`가 없음: `PAYMENT_ID_REQUIRED`
  - `paymentId`가 그 결제와 다름: `PAYMENT_MISMATCH`

  `items`가 없는 요청처럼 계약을 어긴 메시지는 REF-ACCEPT-07을 따른다.
- [x] **REF-ACCEPT-06** 결제당 진행 중(PROCESSING·UNKNOWN)인 `SYSTEM_COMPENSATION`은 1건이다. DB의 부분 UNIQUE로 막는다. 같은 결제에 다른 `refundRequestId`로 보상이 또 오면 FAILED(`COMPENSATION_IN_PROGRESS`)로 저장해 보내고 ERROR 로그를 남긴다. 돈은 움직이지 않는다.
- [x] **REF-ACCEPT-07** 해석할 수 없는 메시지는 `{토픽}.payment.dlt`(`commerce.refund.requests.v1.payment.dlt`)로 보내고, 뒤 메시지를 계속 처리한다. 다음 메시지가 해석할 수 없는 메시지에 해당한다.
  - JSON이 아님
  - 봉투나 키가 계약과 다름
  - `refundRequestId`·`orderRef`·`purpose`·`amount`가 계약과 다름

  일시적인 DB 장애만 offset을 넘기지 않고 계속 재시도한다. 그 밖의 예외는 횟수를 제한해 재시도한 뒤 DLT로 보낸다.

## 실행

- [ ] **REF-EXEC-01** 접수 커밋 직후 실행한다. 끝나지 못한 PROCESSING 환불은 스케줄러가 다시 집는다. 두 실행이 겹쳐도 멱등 키(refund_request_id)로 이중 환불이 없다. (EC 41)
- [ ] **REF-EXEC-02** 성공하면 한 트랜잭션에 다음을 기록한다.
  - 환불 SUCCEEDED
  - 결제 환불액과 상태(PARTIALLY_REFUNDED/REFUNDED)
  - 분개([LED-REFUND-01](ledger.md))
  - `RefundResultRecorded`(SUCCEEDED, `refundedAmount` = 요청 금액)
- [ ] **REF-EXEC-03** 토스가 거절(4xx)하면 FAILED와 `RefundResultRecorded`(FAILED, `refundedAmount` 0)를 기록한다. (EC 42)
- [ ] **REF-EXEC-04** 결과를 모르면 환불을 UNKNOWN으로 둔다. UNKNOWN 결과는 보내지 않는다. 결제 조회로 취소 내역을 확인하고, 없으면 같은 멱등 키로 다시 요청한다. 조회 마감이 지나면 멈추고 대사가 맡는다.

## 시스템 보상과 늦은 승인

- [ ] **REF-LATE-01** `SYSTEM_COMPENSATION` 요청(사유 `LATE_APPROVAL`·`APPROVAL_UNKNOWN` 등)을 받으면 결제 전액 환불을 접수한다. 늦은 승인의 refundRequestId는 `late-{paymentId}`다. 중복 수신은 1건이다. (EC 28, 37)
- [ ] **REF-LATE-02** 시스템 보상의 FAILED 뒤에 더 높은 `resultVersion`으로 SUCCEEDED·NO_CHARGE를 보낼 수 있다. 결제가 승인되지 않아 돌려줄 돈이 없으면 NO_CHARGE(`failureCode: NO_CHARGE`, `refundedAmount` 0)다. USER_REFUND에는 NO_CHARGE를 쓰지 않는다.

## 결과 이벤트

- [ ] **REF-EVENT-01** `RefundResultRecorded`는 상태 변경과 같은 트랜잭션에 공통 outbox로 기록하고, `payment.refund.results.v1`에 orderRef 키로 보낸다. payload는 받은 요청 payload 그대로에 다음 7개 필드를 더한 것이다.
  - `executionId`
  - `resultVersion`: 요청마다 1부터 시작해 결과를 새로 기록할 때마다 1씩 오른다
  - `status`: SUCCEEDED·FAILED·NO_CHARGE
  - `refundedAmount`
  - `resolvedAt`
  - `nextActionAt`: null
  - `failureCode`

  다시 보낼 때 payload는 처음과 같아야 하므로 `resolvedAt`은 DB에 저장한 정밀도로 정한다. 계약 YAML에 예시와 발행 표본이 있다.
