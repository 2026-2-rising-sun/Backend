# 결제 수용 기준

상태: 초안
검증: [결제 재설계 실행 계획](../exec-plans/active/337-payment-redesign/README.md)

결제 생성·결제창·승인·결과 불명 복구·만료를 다룬다. 환불은 [환불](refund.md), 분개는 [원장](ledger.md)에 있다.

- 설계 근거: [이벤트 스토밍 v41](../exec-plans/active/337-payment-redesign/event-storming-v41.html) — `#f1`–`#f6`, `#api`, `#states`, `#toss`, `#mapping`. `EC n`은 그 문서 `#edge` 표의 번호다.
- 지금 코드가 아니라 **교체할 목표 동작**이다. 현재 구현의 기준은 [주문·결제](order-payment.md)에 있고, 교체 PR에서 함께 정리한다([ADR 0337-2](../adr/0337-2-payment-replacement.md)).
- ID는 바꾸지 않는다. 테스트 `@DisplayName`에 ID를 넣는다. 예: `"[PAY-CREATE-03] 같은 orderRef에 금액이 다르면 거절한다"`.
- `(제안)` 표시 오류 코드는 원본에 없어 새로 정한 값이다.

## API

모두 `X-Service-Token` 내부 API다. 응답은 common-module `ApiResponse`. 계약 원본은 PR에서 `contracts/api/payment-service.yaml`이 된다.

| 메서드·경로 | 요청 | 성공 응답 |
|---|---|---|
| `POST /v1/internal/payments` | `orderRef, buyerId(UUID), amount, expiresAt, sellerAmounts[{sellerOrderNumber, sellerId, amount, commissionRate(0–1, 주문 시점 복사)}]` | 201·200 `{paymentId, status, pgOrderId, amount, expiresAt}` |
| `POST /v1/internal/payments/{orderRef}/checkout` | 없음 | 200 `{pgOrderId, amount, expiresAt}` |
| `POST /v1/internal/payments/confirm` | `buyerId, paymentKey, orderId(= pg_order_id), amount` | 200 `{paymentId, status: APPROVED}` · 202 `{paymentId, status: PENDING}` · 422 `{status: DECLINED, code, message}` |
| `GET /v1/internal/payments/{orderRef}` | 없음 | 200 `{paymentId, status, amount, refundedAmount, pgOrderId, approvedAt}` |

## 데이터 불변식

- [ ] **PAY-DATA-01** order_ref는 6–50자이고 결제 1건에 하나다(UNIQUE).
- [ ] **PAY-DATA-02** refunded_amount는 0 이상 amount 이하다.
- [ ] **PAY-DATA-03** 상태는 CREATED·APPROVING·APPROVED·UNKNOWN·CANCELED·PARTIALLY_REFUNDED·REFUNDED 중 하나다.

## 생성 · 결제창 · 조회

- [ ] **PAY-CREATE-01** 새 orderRef로 생성하면 CREATED 결제와 판매자별 금액을 저장하고 첫 pgOrderId `{orderRef}-1`을 준다(201).
- [ ] **PAY-CREATE-02** 같은 orderRef·금액·구매자로 다시 생성하면 기존 결제가 CREATED(만료 전)·APPROVING·UNKNOWN·APPROVED일 때 그 결제를 현재 상태로 돌려준다(200). 동시 요청도 결제 1건이다. (EC 1, 7)
- [ ] **PAY-CREATE-03** 같은 orderRef에 금액이나 구매자가 다르면 409 `AMOUNT_CONFLICT`. (EC 8)
- [ ] **PAY-CREATE-04** 판매자별 금액 합이 amount와 다르면 400 `SUM_MISMATCH`.
- [ ] **PAY-CREATE-05** orderRef 형식이 틀리면 400 `INVALID_ORDER_REF`, expiresAt이 과거면 400 `INVALID_EXPIRES_AT`.
- [ ] **PAY-CREATE-06** 기존 결제가 CANCELED·PARTIALLY_REFUNDED·REFUNDED이거나 만료된 CREATED면 409 `PAYMENT_CLOSED`. 다시 사려면 Commerce가 주문을 새로 만든다.
- [ ] **PAY-CHECKOUT-01** CREATED이고 만료 전이면 checkout_seq를 올리고 새 pgOrderId를 준다. (EC 9)
- [ ] **PAY-CHECKOUT-02** CREATED가 아니거나 만료됐으면 409 `PAYMENT_CLOSED`, 결제가 없으면 404 `NOT_READY`.
- [ ] **PAY-QUERY-01** orderRef로 상태·금액·환불액·pgOrderId·승인 시각을 조회한다. 없으면 404.
- [ ] **PAY-AUTH-01** 내부 API는 `X-Service-Token`이 없거나 다르면 401.

## 승인

입력은 Commerce가 넘긴 buyerId와 브라우저 값 `{paymentKey, orderId(= pg_order_id), amount}`다. 결제는 orderId의 마지막 `-` 앞부분(order_ref)으로 찾는다(order_ref에도 `-`가 있다). 토스 호출은 DB 트랜잭션 밖에서 한다. 검증 실패가 여럿이면 아래 01→02→03 순서와 01 안의 순서(404 → 403 → 400)로 하나만 돌려준다.

- [ ] **PAY-CONFIRM-01** 결제가 없으면 404, 구매자가 다르면 403 `BUYER_MISMATCH`, 금액이 다르면 400 `AMOUNT_MISMATCH`. 토스를 부르지 않는다. (EC 11, 12)
- [ ] **PAY-CONFIRM-02** orderId가 현재 pg_order_id가 아니면 409 `STALE_CHECKOUT`(제안). 토스를 부르지 않는다. (EC 10)
- [ ] **PAY-CONFIRM-03** expires_at이 지났으면 409 `PAYMENT_CLOSED`. 토스를 부르지 않는다. (EC 17)
- [ ] **PAY-CONFIRM-04** 검증을 통과하면 CREATED→APPROVING으로 바꾸고 pg_payment_key를 저장한 뒤 멱등 키 `confirm-{pg_order_id}`로 토스 승인을 부른다. (EC 23)
- [ ] **PAY-CONFIRM-05** 같은 결제에 승인 요청이 동시에 와도 토스 호출은 1번이다. 나머지는 처리 중이면 202, 이미 승인됐으면 200. (EC 13, 14)
- [ ] **PAY-CONFIRM-06** 토스가 성공하고 응답의 status=DONE, orderId, totalAmount, paymentKey가 모두 맞으면 한 트랜잭션에서 APPROVED, 승인 분개([LED-APPROVE-01](ledger.md)), `PaymentApproved` 이벤트를 기록한다(200).
- [ ] **PAY-CONFIRM-07** 토스는 성공했는데 응답 값이 하나라도 다르면 APPROVED로 바꾸지 않고 전액 취소(멱등 키 `cancel-{pg_order_id}`) 후 CANCELED, 알림(ERROR 로그). 409 `PAYMENT_CLOSED`. 취소 결과가 불명이어도 CANCELED로 두고 거래 대사가 다시 잡는다(감수, EC 25·38).
- [ ] **PAY-CONFIRM-08** 토스가 거절하면 CREATED로 돌아가고 422 `DECLINED {code, message}`. 새 결제창으로 다시 승인할 수 있다. (EC 15, 16)
- [ ] **PAY-CONFIRM-09** 요청이 토스에 가지 않았으면(연결 실패) CREATED로 돌아가고 503 `PG_UNAVAILABLE`(제안).
- [ ] **PAY-CONFIRM-10** 결과를 모르면 같은 멱등 키로 1번 더 부르고, 그래도 모르면 UNKNOWN(첫 조회 10초 뒤, 조회 마감 30분 뒤)으로 두고 202. (EC 21)
- [ ] **PAY-CONFIRM-11** 토스 응답을 8초 안에 받지 못하면 202를 먼저 돌려주고 처리는 계속한다. 그동안 결제는 APPROVING이고, 결과가 나오면 PAY-CONFIRM-06–10과 같이 반영한다. 이후 거절되면 CREATED, 승인되면 이벤트가 나간다. (EC 18, 19)
- [ ] **PAY-CONFIRM-12** 토스 호출마다 호출 기록(pg_call)을 1행 남긴다.

## PG 응답 분류

- [ ] **PAY-PG-01** 연결 타임아웃(3초)·연결 거부, 그리고 우리 설정 문제나 호출 제한으로 토스가 처리하지 않은 401·403·429는 "보내지 않음"이다. 구매자에게 카드 거절로 보이지 않게 한다.
- [ ] **PAY-PG-02** 그 밖의 4xx는 "거절"이다. 단 `ALREADY_PROCESSED_PAYMENT`·`ALREADY_PROCESSING_REQUEST`는 "불명"이다.
- [ ] **PAY-PG-03** 읽기 타임아웃·응답 중 끊김·5xx·분류하지 못한 예외는 "불명"이다(애매하면 불명).
- [ ] **PAY-PG-04** 승인·조회·취소 모두 Basic 인증과 `Idempotency-Key`(승인·취소)를 보내고, 읽기 타임아웃은 60초다.

## 결과 불명 복구 · 만료

- [ ] **PAY-RECOVER-01** APPROVING이 3분 넘게 남아 있으면 UNKNOWN으로 옮겨 조회 대상에 넣는다. (EC 22)
- [ ] **PAY-RECOVER-02** UNKNOWN 결제를 저장된 paymentKey로 조회한다. DONE이면 PAY-CONFIRM-06과 같은 처리를 한다. 주문이 이미 닫혔어도 같다. (EC 21, 37)
- [ ] **PAY-RECOVER-03** 조회 결과가 ABORTED·EXPIRED면 CREATED, 그 밖이면 UNKNOWN을 유지하고 10초·30초·1분·5분 간격으로 다시 조회한다.
- [ ] **PAY-RECOVER-04** 조회 마감이 지나면 조회를 멈추고 알림(ERROR 로그)을 남긴다. 이후는 거래 대사가 맡는다.
- [ ] **PAY-EXPIRE-01** 만료 시각이 지난 CREATED 결제는 CANCELED가 된다. 승인과 동시에 일어나도 한쪽만 반영된다. (EC 31, 35)
- [ ] **PAY-RECOVER-05** 여러 인스턴스가 같은 결제를 동시에 처리해도 상태 변경·분개·이벤트는 1번이다.

## 결과 이벤트

- [ ] **PAY-EVENT-01** `PaymentApproved {paymentId, orderRef, amount, approvedAt}`를 `payment.execution-results.v1`에 orderRef 키로 보낸다. 상태 변경과 같은 트랜잭션에서 outbox에 쓴다. 발행 전 재시작 뒤 발행(EC 30)은 common-module `OutboxTransactionIntegrationTest`가 검증한다.
- [ ] **PAY-EVENT-02** 이벤트 계약 YAML에 예시가 있고, 실제 발행 객체로 만든 표본이 계약 검사를 통과한다.

## 범위 밖

토스 호출 동시 실행 수 제한(벌크헤드)·웹훅·2차 PG·서킷 브레이커(필요할 때 별도 요구사항), 주문 만료와 재고 반환(Commerce).
