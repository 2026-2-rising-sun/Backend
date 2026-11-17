# #338 토스 결제 승인과 원장 분개

상태: 설계 확정(2026-11-15)
이슈: [#338](https://github.com/2026-2-rising-sun/Backend/issues/338) · Phase 문서: [Phase 1 결제 1차](../phase1-payment.md) · 스키마: [#348 설계](348-create-api.md#db-v4__payment_coresql)

## 개요

끝나면 Commerce가 중계한 승인 요청을 Payment가 검증하고, **토스 승인을 한 번만 호출**하고, 결과를 상태·원장·이벤트에 **한 트랜잭션으로** 남긴다. 토스 응답이 8초 안에 없으면 202를 먼저 돌려주고 처리는 계속한다. 결과를 모르면 UNKNOWN으로 두고 #349가 확정한다. 이 이슈의 "승인 반영" 함수(`applyDone`)를 #349가 그대로 쓴다.

## 범위

| 포함 | 요구사항 ID |
|---|---|
| 원장 서비스 | LED-01–05, LED-APPROVE-01(분개 내용은 승인 처리 PR에서 호출·검증) |
| 토스 클라이언트 | PAY-PG-01–04 |
| 승인 처리(서비스) | PAY-CONFIRM-01–10, 12, PAY-EVENT-01–02 |
| 승인 API · 8초 | PAY-CONFIRM-11, 승인 HTTP 매핑 |

하지 않는 것: 결과 불명 조회·정체 스위퍼·만료(#349), 환불(Phase 3), 웹훅, 동시 호출 수 제한.

## 구성 요소

```text
payment/controller   ApprovalController            POST /v1/internal/payments/{orderRef}/approval
payment/service      ApprovalService               start(①검증·APPROVING) · process(②토스 → ③반영). 8초 대기는 PR6
                     PaymentOutcomeService         ③반영 함수들: insertCall · applyDone · applyDeclined · applyNotSent · applyUnknown · compensate
client/toss          TossPaymentsClient            confirm · getPayment · cancel. 결과 분류(TossResult)
ledger/service       LedgerService                 record(refType, refId, lines) → boolean. 호출자 트랜잭션에 참여
```

## 1. 토스 클라이언트 (`client.toss`)

### 설정 (제안 이름)

| 키 | 환경 변수 | 기본값 | 의미 |
|---|---|---|---|
| `payment.toss.base-url` | `PAYMENT_TOSS_BASE_URL` | `https://api.tosspayments.com` | |
| `payment.toss.secret-key` | `PAYMENT_TOSS_SECRET_KEY` | 빈 값 | 테스트 키 `test_sk_…`. **로그·예외 메시지에 남기지 않는다**. 비어 있으면 기동하고 WARN 로그 한 줄을 남긴 뒤, 모든 호출을 보내지 않고 `NotSent`로 돌려준다(2026-11-15 사용자 결정) |
| `payment.toss.connect-timeout` | — | `3s` | PAY-PG-01 |
| `payment.toss.read-timeout` | — | `60s` | PAY-PG-04. 헤더와 본문을 합친 응답 대기다. JDK 요청 타임아웃은 헤더까지만 막으므로 호출 전체에 `connect-timeout + read-timeout` 데드라인을 두고, 넘으면 `Unknown`이다 |

HTTP 클라이언트는 Spring `RestClient`(+ JDK `HttpClient` 요청 팩토리)로 만든다. 공통 `ServiceRestClientFactory`는 내부 서비스용이라 쓰지 않는다. Boot가 주입하는 `RestClient.Builder`도 쓰지 않는다. 공통 커스터마이저가 내부 요청 ID(`X-Request-Id`) 같은 헤더를 붙이기 때문이다. 외부 PG에는 토스 헤더만 보낸다.

### 호출 3개

| 메서드 | 토스 API | 본문 | `Idempotency-Key` |
|---|---|---|---|
| `confirm(paymentKey, orderId, amount, key)` | `POST /v1/payments/confirm` | `{paymentKey, orderId, amount}` (orderId·amount는 **DB 값**) | `confirm-{pg_order_id}` |
| `getPayment(paymentKey)` | `GET /v1/payments/{paymentKey}` | — | — |
| `cancel(paymentKey, reason, key)` | `POST /v1/payments/{paymentKey}/cancel` | `{cancelReason}` (금액 없음 = 전액) | `cancel-{pg_order_id}` |

공통 헤더: `Authorization: Basic base64(secretKey + ":")`, `Content-Type: application/json`.

### 응답에서 쓰는 필드 (토스 Payment 객체)

| 필드 | 쓰는 곳 |
|---|---|
| `paymentKey` | 응답 검증 |
| `orderId` | 응답 검증(= `pg_order_id`) |
| `status` | `DONE` 판단, `pg_status` 저장 |
| `totalAmount` | 응답 검증(= `amount`) |
| `approvedAt` | `approved_at`, 이벤트 |
| `isPartialCancelable` | `partial_cancelable` |

오류 응답은 `{code, message}`다. 모르는 필드는 무시한다(`FAIL_ON_UNKNOWN_PROPERTIES=false`).

### 결과 분류 `TossResult`

`Success(payment)` · `Declined(code, message)` · `NotSent(reason)` · `Unknown(reason)` 네 가지다. **위에서부터 처음 맞는 줄**로 고른다.

| 상황 | 결과 | 근거 |
|---|---|---|
| 시크릿 키 미설정(호출하지 않음) | `NotSent` | PAY-PG-01 |
| 연결 타임아웃, 연결 거부(요청을 보내기 전 예외) | `NotSent` | PAY-PG-01 |
| 읽기 타임아웃, 응답 중 끊김, HTTP 5xx, 분류하지 못한 예외 | `Unknown` | PAY-PG-03. 애매하면 불명 |
| HTTP 401 (`UNAUTHORIZED_KEY`), HTTP 429 | `NotSent` | 우리 키 문제, 호출 제한. 상태 코드만 본다(본문 code 불필요) |
| HTTP 4xx인데 본문에서 `code`를 읽지 못함 | `Unknown` | 무엇이 거절됐는지 모른다 |
| HTTP 400 `INVALID_API_KEY`, HTTP 403 + code ∈ {`INCORRECT_BASIC_AUTH_FORMAT`, `API_KEY_ACCESS_DENIED`} | `NotSent` | 우리 키·설정 문제 |
| HTTP 400 + code ∈ {`ALREADY_PROCESSED_PAYMENT`, `ALREADY_PROCESSING_REQUEST`}, HTTP 403 `FORBIDDEN_CONSECUTIVE_REQUEST`, HTTP 409 `IDEMPOTENT_REQUEST_PROCESSING` | `Unknown` | PAY-PG-02. 앞선 요청이 처리 중일 수 있다. 409는 같은 멱등 키 요청이 처리 중이다([토스 멱등 키](https://docs.tosspayments.com/reference/using-api/idempotency-key)) |
| 그 밖의 HTTP 4xx (카드 거절 403 `REJECT_CARD_COMPANY` 등 포함) | `Declined(code, message)` | PAY-PG-02 |
| HTTP 200, 본문 `status`가 호출별 기대 상태 | `Success(payment)` | 값 검증은 ③에서 |
| HTTP 200, 본문 파싱 실패 또는 `status`가 기대 상태가 아님(`IN_PROGRESS` 등) | `Unknown` | 조회로 확정한다. 보상 취소로 보내지 않는다 |
| 그 밖의 HTTP 상태(1xx·3xx·200이 아닌 2xx) | `Unknown` | 분류하지 못한 응답 |

200의 기대 상태는 호출마다 다르다. `confirm`은 `DONE`, `cancel`은 `CANCELED`(전액 취소)다. `getPayment`는 본문만 읽히면 어떤 `status`든 `Success`로 돌려주고 판단은 호출자(#349의 PAY-RECOVER-02–03)가 한다. 4xx·5xx·예외 줄은 세 호출에 같게 적용한다.

> **요구사항 수정:** 기존 PAY-PG-01은 "401·403·429는 보내지 않음"이었다. 토스 승인 API는 카드 거절 대부분(`REJECT_CARD_PAYMENT`, `REJECT_CARD_COMPANY`, `FDS_ERROR` 등)을 **403**으로 준다([토스 오류 코드](https://docs.tosspayments.com/reference/error-codes), 2026-11-15 확인). 그대로 두면 카드 거절이 "결제사 연결 불가"로 보인다. 403은 위 2개 코드만 "보내지 않음"으로 보도록 요구사항 문장을 이 PR에서 함께 고친다. 돈 쪽 결과는 같다(둘 다 CREATED로 돌아간다). 바뀌는 것은 응답 코드(503 → 422)뿐이다.

## 2. 승인 처리

### 입력

`POST /v1/internal/payments/{orderRef}/approval`

| 필드 | 의미 | 형식·제약 | 예시 |
|---|---|---|---|
| 경로 `orderRef` | 결제 찾기 키 | #348 형식 | `OD-20261110-K7Q2M9XA` |
| `buyerId` | Commerce가 로그인 토큰에서 꺼낸 구매자 | 필수, UUID | |
| `paymentKey` | 브라우저가 받은 토스 paymentKey | 필수, ≤ 200자 | `tgen_…` |
| `pgOrderId` | 브라우저가 받은 토스 orderId | 필수, ≤ 64자 | `OD-20261110-K7Q2M9XA-1` |
| `amount` | 브라우저가 받은 금액 | 필수, ≥ 1 | `30000` |

### 순서와 트랜잭션

```text
① 트랜잭션 A (검증 + 시작)        ② 트랜잭션 밖 (가상 스레드)        ③ 트랜잭션 C (반영)
   검증 → CREATED→APPROVING          토스 confirm (같은 키로 1번 더)      결과별 반영 + pg_call 결과
   pg_call(CONFIRM, UNKNOWN) INSERT  재호출이면 그 전에 pg_call 1행 INSERT  (+ 분개 + outbox)
   커밋                                                                 커밋
요청 스레드: ②③을 가상 스레드에 맡기고 최대 8초 기다린다. 끝나면 결과 응답, 아니면 202.
```

**① 검증** (요구사항 순서. 처음 걸린 하나만 응답)

| # | 검사 | 실패 응답 | 토스 호출 |
|---|---|---|---|
| 1 | `orderRef`로 결제 찾기 | 404 `PAYMENT_NOT_FOUND` | 안 함 |
| 2 | `pgOrderId`의 마지막 `-` 앞부분 = `orderRef` | 400 `ORDER_ID_MISMATCH` | 안 함 |
| 3 | `buyerId` = `buyer_id` | 403 `BUYER_MISMATCH` + WARN 보안 로그 | 안 함 |
| 4 | `amount` = `amount` | 400 `AMOUNT_MISMATCH` + WARN 보안 로그 | 안 함 |
| 5 | `pgOrderId` ≠ 현재 `pg_order_id`(현재 값이 NULL이어도) | 409 `STALE_CHECKOUT` (PAY-CONFIRM-02) | 안 함 |
| 6 | 상태: APPROVED → **200**(같은 응답 반복), APPROVING·UNKNOWN → **202**, CANCELED·PARTIALLY_REFUNDED·REFUNDED → 409 `PAYMENT_CLOSED` | | 안 함 |
| 7 | CREATED인데 `expires_at` ≤ now() | 409 `PAYMENT_CLOSED` (PAY-CONFIRM-03) | 안 함 |

요구사항 순서(01 → 02 → 03)를 따른다. 다만 만료 검사(7)는 CREATED에만 한다. 이미 승인된 결제는 만료 시각이 지나도 새로고침에 200을 줘야 하기 때문이다(PAY-CONFIRM-05).

검사를 통과하면 조건부 UPDATE로 시작한다. 동시 요청은 여기서 한 건만 1행이 된다(PAY-CONFIRM-05).

```sql
UPDATE payment
   SET status = 'APPROVING', approving_at = now(), pg_payment_key = :paymentKey, updated_at = now()
 WHERE id = :id AND status = 'CREATED' AND pg_order_id = :pgOrderId
   AND amount = :amount AND buyer_id = :buyerId AND expires_at > now();
-- 0행: 다시 읽어 표 5–7로 응답(경합에서 진 요청)
-- 1행: 같은 트랜잭션에서 pg_call(kind=CONFIRM, outcome=UNKNOWN, idempotency_key=confirm-{pg_order_id}, requested_at=now()) INSERT
```

`pg_payment_key` UNIQUE 위반(다른 결제가 이미 쓴 paymentKey)은 정상이라면 생기지 않는다. 400 `INVALID_REQUEST` + WARN 보안 로그로 거절한다.

**② 토스 호출** — 트랜잭션 밖. `confirm`이 `Unknown`이면 같은 키로 **1번 더** 부른다(PAY-CONFIRM-10). 두 번째 호출도 pg_call 1행을 남긴다(① 이후 ③ 전에 별도 짧은 트랜잭션으로 INSERT).

**한 번이라도 `Unknown`을 받은 시도는 CREATED로 되돌리지 않는다.** 첫 요청이 토스에 닿았을 수 있기 때문이다. 두 번째 결과가 `Success`면 그대로 반영하고, `Declined`·`NotSent`·`Unknown`이면 모두 아래 표의 `Unknown` 줄로 처리해 조회로 확정한다. 아래 표의 `Declined`·`NotSent` 줄은 **첫 호출의 결과**일 때만 쓴다.

**③ 반영** (`PaymentOutcomeService`, 결과마다 한 트랜잭션)

| 결과 | 상태 변경(조건부 UPDATE) | 함께 쓰는 것 | 승인 API 응답 |
|---|---|---|---|
| `Success` + 응답 검증 통과 | `APPROVED`, `approved_at`, `pg_status`, `partial_cancelable` · `WHERE id AND status IN ('APPROVING','UNKNOWN')` | 분개(아래), outbox `PaymentApproved`, pg_call `SUCCESS` | 200 `{paymentId, status: APPROVED}` |
| `Success` + 응답 검증 실패 | 아래 "보상 취소" | pg_call `SUCCESS` + CANCEL 행, ERROR 로그 | 409 `PAYMENT_CLOSED` |
| `Declined`(첫 호출) | `CREATED`, `approving_at = NULL`, **`pg_order_id = NULL`** · `WHERE id AND status = 'APPROVING'` | pg_call `DECLINED` + `result_code` | 422 `DECLINED` |
| `NotSent`(첫 호출) | 같은 변경 | pg_call `NOT_SENT` | 503 `PG_UNAVAILABLE` |
| `Unknown`, 또는 재호출 뒤 `Success`가 아닌 모든 결과 | `UNKNOWN`, `next_check_at = now() + 10s`, `check_deadline_at = now() + 30m` · `WHERE status = 'APPROVING'` | 첫 호출 pg_call은 `UNKNOWN` 유지. 재호출 pg_call에는 그 호출의 결과(`DECLINED`·`NOT_SENT`·`UNKNOWN`)를 남긴다 | 202 `{paymentId, status: PENDING}` |

- **pg_call `http_status`**(2026-11-17 추가): 토스 응답을 받은 호출은 그 HTTP 상태(성공 200, 거절 4xx, 5xx, 200인데 기대 상태가 아님 등)를 남긴다. 응답을 받지 못했거나(연결 실패·읽기 타임아웃·데드라인·키 미설정) 본문을 끝까지 받지 못하면(연결 끊김, JSON이 중간에서 끝남) NULL이다. `TossResult.httpStatus()`가 이 값을 나르고 분류 규칙은 바뀌지 않는다. 첫 호출이 불명이면 재호출 전에 그 행의 `http_status`만 채우고 결과는 `UNKNOWN`으로 둔다. 이 브랜치에서는 CONFIRM·CANCEL 기록과 `applyDone`(성공이면 200)이 이 규칙을 따른다. QUERY의 나머지 결과 기록은 #349 조회기가 같은 규칙으로 남긴다.
- 응답 검증(PAY-CONFIRM-06): `status = DONE`, `orderId = pg_order_id`, `totalAmount = amount`, `paymentKey = 보낸 값`. 하나라도 다르면 실패다.
- **CREATED로 되돌릴 때 `pg_order_id`를 NULL로 비운다.** 같은 pgOrderId로 다시 승인하면 멱등 키 `confirm-{pg_order_id}`가 재사용되어 토스가 첫 거절 응답을 돌려줄 수 있다. 비워 두면 그 요청은 409 `STALE_CHECKOUT`이 되고, Commerce는 `POST …/checkouts`로 새 결제창을 연다(PAY-CONFIRM-08 "새 결제창으로 다시 승인").
- `applyDone`의 조건부 UPDATE가 **0행이면 다시 읽는다.** APPROVED면 이미 반영된 것이므로 끝낸다(분개·outbox 없음, 중복 없음). 그 밖의 상태(CREATED·CANCELED 등)면 토스에서는 승인됐는데 우리는 다른 상태라는 뜻이다. ERROR 로그를 남기고 아래 보상 취소로 보낸다. 돈만 빠진 채 끝나는 경로를 남기지 않는다.
- **`applyDone(paymentId, pgCallId, tossPayment) → boolean`은 #349의 조회 DONE도 같은 함수로 처리한다.** 승인 반영 코드는 한 곳에만 있다. 응답은 DB 값(`pg_order_id`·`amount`·`pg_payment_key`)과 비교한다. 결제가 이미 APPROVED면 응답 검증 없이 true로 끝낸다(승인된 결제는 보상 취소하지 않는다). false면 호출자가 트랜잭션 밖에서 `compensate`를 부른다. 토스 응답에 `approvedAt`이 없으면 DB `now()`를 쓴다.

**보상 취소**(PAY-CONFIRM-07): 취소 대상은 **토스 응답의 `paymentKey`**다(DB 값이 아니다. 검증 실패는 토스가 다른 결제를 응답했을 수 있다는 뜻이다). 순서: ⓐ 한 트랜잭션에서 `CANCELED`로 선점(`WHERE status IN ('APPROVING','UNKNOWN','CREATED')` 조건부 UPDATE 한 번) + pg_call(kind=CANCEL, outcome=UNKNOWN, idempotency_key=`cancel-{시도의 pg_order_id}`) INSERT 커밋. 0행이면 다시 읽어 CANCELED가 아니면(APPROVED·환불 상태) 다른 반영이 먼저 승인한 것이므로 **취소하지 않고 끝낸다** → ⓑ 트랜잭션 밖에서 `cancel(응답 paymentKey, "승인 응답 불일치", 키)` → ⓒ pg_call에 결과(outcome·result_code)를 남긴다. 선점을 먼저 하므로 취소 도중 `applyDone`이 승인으로 바꾸지 못한다(2026-11-16 리뷰 반영, 처음 설계는 ⓒ에서 CANCELED). 시도의 pg_order_id는 ①에서 읽어 메모리에 들고 있는 값을 쓴다(DB 값은 이미 NULL일 수 있다). 분개는 쓰지 않는다(확정된 돈이 없다고 본다). **보상 취소가 `Success`가 아니면(거절·보내지 않음·불명) 자동 재시도 없이 사람이 확인한다**: DB는 CANCELED인데 토스 결제가 남았을 수 있으므로 `[사람 확인 필요]` ERROR 로그에 paymentId·orderRef·paymentKey·금액·결과를 남긴다(감수, 거래 대사가 다시 잡는다).

### 8초 대기 (PAY-CONFIRM-11)

- 요청 스레드는 ②③ 작업을 `Executors.newVirtualThreadPerTaskExecutor()`(Java 21)로 넘기고 `future.get(8, SECONDS)`로 기다린다.
- 8초 안에 끝나면 ③의 응답을 준다. 넘으면 **202 `{paymentId, status: PENDING}`**과 헤더 `Location: /v1/internal/payments/{orderRef}`를 주고, 작업은 계속 돈다(결과는 이벤트로 나간다).
- 설정: `payment.approval.response-wait` 기본 `8s`(제안).
- 동시 실행 수 제한은 두지 않는다(벌크헤드 범위 밖). ADR 0337-5의 "크기를 제한한 별도 스레드 풀" 문장은 이 결정에 맞게 정정 메모를 단다(이 PR).

### 응답 예시

```json
// 200
{ "success": true, "data": { "paymentId": 1001, "status": "APPROVED" }, "error": null }
// 202  (Location: /v1/internal/payments/OD-20261110-K7Q2M9XA)
{ "success": true, "data": { "paymentId": 1001, "status": "PENDING" }, "error": null }
// 422 — 요구사항 표의 {status, code, message}를 data에 그대로 담는다
{ "success": false,
  "data": { "status": "DECLINED", "code": "REJECT_CARD_PAYMENT", "message": "한도초과 혹은 잔액부족으로 결제에 실패했습니다." },
  "error": { "code": "DECLINED", "message": "카드사에서 결제를 거절했습니다.", "requestId": "…" } }
```

422는 `error.code = DECLINED`로 고정하고, 토스 코드·메시지는 요구사항 표대로 `data.code`·`data.message`에 담는다.

### 승인 API 오류 코드 전체

| HTTP | code | 언제 |
|---|---|---|
| 200 | — | 승인됨(이미 승인된 결제의 재요청 포함) |
| 202 | — | 처리 중(8초 초과, APPROVING·UNKNOWN, 결과 불명) |
| 400 | `INVALID_REQUEST` | 입력 형식, paymentKey 중복 |
| 400 | `ORDER_ID_MISMATCH`(제안) | pgOrderId가 경로 orderRef 것이 아님 |
| 400 | `AMOUNT_MISMATCH` | 금액 다름 |
| 401 | `UNAUTHORIZED` | 서비스 토큰 |
| 403 | `BUYER_MISMATCH` | 구매자 다름 |
| 404 | `PAYMENT_NOT_FOUND` | 결제 없음 |
| 409 | `STALE_CHECKOUT`(제안) | 예전 결제창 |
| 409 | `PAYMENT_CLOSED` | 만료·닫힘·응답 검증 실패 뒤 보상 |
| 422 | `DECLINED` | 토스 거절 |
| 503 | `PG_UNAVAILABLE`(제안) | 토스에 요청이 가지 않음 |

## 3. 원장 (`ledger.service`)

```java
// 호출자 트랜잭션 필수 (Propagation.MANDATORY, LED-04). 새로 기록하면 true, 이미 있으면 false
boolean record(LedgerRefType refType, String refId, List<Journal.Line> lines)
record Line(LedgerAccount account, long debit, long credit, String sellerId) {}
```

- `Journal`·`Journal.Line` 생성자가 줄 2개 이상, 각 줄 한쪽만 > 0(음수 거절), `SELLER_PAYABLE` 줄의 판매자 ID(LED-02), 차변 합 = 대변 합(`Math.addExact`로 넘침 거절)을 검사한다. 어기면 `IllegalArgumentException`, DB에 닿기 전이라 아무것도 저장하지 않는다(LED-01).
- `INSERT INTO ledger_journal … ON CONFLICT DO NOTHING`(충돌 대상 생략. H2 PostgreSQL 모드에서 확인된 형태) → 0행이면 이미 기록된 사건이므로 줄을 넣지 않고 false로 끝낸다(LED-03). H2 `create-drop`이 같은 UNIQUE를 만들도록 `LedgerJournal`에 `@UniqueConstraint`를 둔다.
- 수정·삭제 메서드를 만들지 않는다. 원장 리포지토리는 `JpaRepository`가 아닌 `Repository`로 좁혀 삭제 메서드를 물려받지 않는다(LED-05).

**결제 승인 분개**(LED-APPROVE-01): 사건 키 (`PAYMENT_APPROVED`, `payment.id` 문자열)

| 계정 | 차변 | 대변 |
|---|---|---|
| `PG_CUSTODY` (PG 보관금) | amount | |
| `BUYER_DEPOSIT` (구매자 예수금) | | amount |

## 4. 이벤트 `PaymentApproved` (PAY-EVENT-01–02)

| 항목 | 값 |
|---|---|
| 토픽 | `payment.execution-results.v1` (기존 토픽 그대로) |
| Kafka 키 = `aggregateId` | `orderRef` (문자열. 기존 계약의 `format: uuid` 제거) |
| 발행 | ③ 승인 반영 트랜잭션 안에서 `EventOutbox.append(topic, envelope)` |
| 봉투 | `EventEnvelope.create("PaymentApproved", 1, "payment-service", orderRef, now, payload)` |

payload:

| 필드 | 의미 | 형식 | 예시 |
|---|---|---|---|
| `paymentId` | Payment 결제 ID | int64 ≥ 1 | `1001` |
| `orderRef` | 주문 참조 | string 6–50 | `OD-20261110-K7Q2M9XA` |
| `amount` | 결제 금액 | int64 ≥ 1 | `30000` |
| `approvedAt` | 토스 승인 시각 | date-time | `2026-11-10T14:02:11+09:00` |

```json
{ "eventId": "8f0c2d4e-6b1a-4c3e-9f7d-2a5b8c1e0d93", "eventType": "PaymentApproved", "schemaVersion": 1,
  "source": "payment-service", "aggregateId": "OD-20261110-K7Q2M9XA", "occurredAt": "2026-11-10T05:02:11Z",
  "payload": { "paymentId": 1001, "orderRef": "OD-20261110-K7Q2M9XA", "amount": 30000,
               "approvedAt": "2026-11-10T14:02:11+09:00" } }
```

- `contracts/events/payment-execution-results.yaml`의 `PaymentExecutionResolved`를 `PaymentApproved`로 **교체**한다. 메시지마다 `examples`를 둔다.
- 발행 객체로 만든 표본을 `build/contract-samples`에 남기는 직렬화 테스트가 계약 검사를 통과해야 한다(PAY-EVENT-02). #347이 옛 DTO 없이 표본만 만드는 형태로 남긴 `ExecutionEventContractTest`를 지우고 `PaymentApprovedContractTest`를 새로 만든다(표본 저장 방식은 같다).
- 발행 전 재시작 뒤 발행(EC 30)은 common-module `OutboxTransactionIntegrationTest`가 이미 검증한다.

## 테스트

| ID | 테스트(제안 이름) | 종류 |
|---|---|---|
| LED-01–05 | `JournalTest`(검사), `LedgerServiceTest`(H2 저장·롤백·메서드), `LedgerServicePostgresTest`(중복·동시 중복·롤백) | 단위 · 통합(H2) · `@Tag("postgres")` |
| LED-APPROVE-01 | `ApprovalOutcomePostgresTest` 안 분개 검증 | `@Tag("postgres")` |
| PAY-PG-01–04 | `TossPaymentsClientTest`(`MockRestServiceServer`), `TossResultClassifierTest`(예외·상태·코드 → 결과) | 단위 |
| PAY-CONFIRM-01–03 | `ApprovalValidationTest`(토스 호출 0번 검증) | 통합 |
| PAY-CONFIRM-04, 05 | `ApprovalConcurrencyPostgresTest`(동시 2건 → 토스 1번) | `@Tag("postgres")` |
| PAY-CONFIRM-06–10, 12 | `ApprovalOutcomePostgresTest`(결과 5종 × 상태·pg_call·분개·outbox 행 수) | `@Tag("postgres")` |
| PAY-CONFIRM-11 | `ApprovalResponseWaitTest`(토스 지연 → 202 + Location, 이후 APPROVED) | 통합 |
| PAY-EVENT-01–02 | `PaymentApprovedContractTest`(표본 생성) | 단위 |

토스는 항상 `MockRestServiceServer` 또는 테스트 대역으로 대체한다. 실제 토스 테스트 키 호출은 Phase 2 끝의 수동 확인에서 한다.

## 바뀌는 점

| 구분 | 유지 | 바뀜·새로 생김 | 없어짐 |
|---|---|---|---|
| API | 결제 키 `orderRef` | 승인 API 1개 | — |
| 외부 | — | 토스 승인·조회·취소 클라이언트, 시크릿 키 설정 | Mock 실행기(#347에서 이미 삭제) |
| 이벤트 | 토픽, outbox 릴레이 | `PaymentApproved`(키 orderRef) | `PaymentExecutionResolved` |
| DB | V4 스키마 | — | — |
| 운영 | — | `PAYMENT_TOSS_SECRET_KEY` 환경 변수(없으면 기동은 하고 토스 호출은 `NotSent`) | — |
| 요구사항 | — | PAY-PG-01 문장(403 분류, 400 `INVALID_API_KEY`, 401·429는 상태 코드만, 키 미설정), PAY-PG-02 문장(409 `IDEMPOTENT_REQUEST_PROCESSING`) | — |

CommerceCore는 아직 `PaymentExecutionResolved`를 기다린다. Phase 2 전까지 통합 환경의 결제는 동작하지 않는다(이미 알려진 영향).

**호환 깨짐을 감수한다(2026-11-15 사용자 결정).** PR5는 옛 메시지를 남기지 않고 지운다. CommerceCore의 `PaymentEventConsumerConfiguration`과 통합 테스트 2개(`PaymentResultKafkaPostgresTest`, `PaymentRecoveryPostgresTest`)가 깨지면, PR5에서 컴파일·검사가 통과할 만큼만 고친다(옛 소비 코드는 Phase 2에서 다시 쓴다). PR 본문과 CommerceCore 담당자에게 깨지는 내용을 알린다.

## PR 나누기

| PR | 범위 | base | 브랜치 | 갱신할 문서 |
|---|---|---|---|---|
| PR3 원장 | `ledger/service`, `Journal` | #348 PR2 | `feat/#338-payment-ledger` | — |
| PR4 토스 클라이언트 | `client/toss`, 설정 | `dev`(병렬, PR5 전에 병합) | `feat/#338-toss-client` | — |
| PR5 승인 처리 | `ApprovalService`·`PaymentOutcomeService`(컨트롤러 없음), 이벤트 계약 교체 | PR3(PR4 브랜치를 merge로 포함) | `feat/#338-payment-approval` | `states/payment.md`·`.html` |
| PR6 승인 API · 8초 | `ApprovalController`, 8초 대기, 계약 YAML | PR5 | `feat/#338-payment-approval-api` | `api.html` |

PR5와 PR6을 나누는 이유: 8초 대기 없이 엔드포인트가 먼저 배포되면 Payment가 Commerce 10초보다 오래 기다린다([ADR 0337-5](../../../../adr/0337-5-pg-timeout-and-unknown.md)). PR6이 #338을 `Closes`한다.

## 미결정

결정(2026-11-15, 사용자 동의):

1. 설정 키는 `payment.toss.*`, `payment.approval.response-wait`로 한다.
2. 보상 취소 뒤 상태는 CANCELED다.
3. 옛 이벤트는 호환을 깨고 지운다(위 "바뀌는 점" 아래 문단).

## 참고

[결제 요구사항](../../../../requirements/payment.md) · [원장 요구사항](../../../../requirements/ledger.md) · ADR [0337-3](../../../../adr/0337-3-payment-result-delivery.md)·[0337-4](../../../../adr/0337-4-ledger-double-entry.md)·[0337-5](../../../../adr/0337-5-pg-timeout-and-unknown.md) · 목표 설계 [`#f2`](../../../../design-docs/services/payment/design.html#f2)·[`#f4`](../../../../design-docs/services/payment/design.html#f4)·[`#contract`](../../../../design-docs/services/payment/design.html#contract)
