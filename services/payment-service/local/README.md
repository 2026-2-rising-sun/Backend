# Payment 로컬 수동 검증

Payment를 로컬에서 띄우고 내부 API로 결제 생성 → 결제창 → 승인을 직접 돌려 보는 도구다. 자동 테스트를 대신하지 않는다. 모드 A는 가짜 토스(WireMock, local 프로필 기본)로 실패 경로를, 모드 B는 실제 토스 테스트 키(`toss-real` 프로필)로 결제창부터 승인까지를 본다.

| 파일 | 용도 |
|---|---|
| `payment.http` | IntelliJ HTTP Client 요청(생성·조회·결제창·승인). 위쪽 변수만 바꿔 시나리오를 돌린다 |
| 가짜 토스 | Infra/local의 `toss-mock`(localhost:59080). 응답 정의는 Infra `local/mocks/toss/mappings`에 있다. 정해진 `paymentKey`로 거절·지연·5xx·금액 불일치를 고르고, 그 밖의 키는 요청 값 그대로 승인한다 |
| `toss-sandbox/` | 실제 토스 결제창 페이지. `serve.sh`가 `.env`의 클라이언트 키로 띄운다 |

명령은 저장소 루트에서 실행한다.

## 준비

1. Infra 저장소에서 로컬 스택을 띄운다. Postgres 5432(`shoppinglive-postgres`), Kafka 29092, 가짜 토스 `toss-mock`(localhost:59080)이 함께 뜬다. 자세한 내용은 [Infra local README](https://github.com/2026-2-rising-sun/Infra/blob/main/local/README.md)에 있다. payment DB가 없으면 그 문서의 "서비스별 DB 생성"대로 만든다. `down -v`는 다른 서비스 데이터까지 지우므로 쓰지 않는다.

   ```bash
   docker compose -f local/docker-compose.yml up -d   # Infra 저장소 루트에서
   ```

   `scripts/local/msa-compose.yml`(Postgres 56432, Kafka 59092)을 쓰면 `.env`의 DB·Kafka 네 줄을 지우고, 가짜 토스만 Infra에서 `docker compose -f local/docker-compose.yml up -d toss-mock`으로 띄운다.
2. `cp services/payment-service/.env.example services/payment-service/.env` 후 DB 비밀번호를 채운다. 모드 B는 토스 키도 채운다. `.env`는 커밋하지 않는다.
3. 앱을 띄운다. local 프로필이 `.env`를 읽는다. 실제 토스는 `--spring.profiles.active=local,toss-real`이다.

   ```bash
   ./gradlew :services:payment-service:bootRun --args='--spring.profiles.active=local'
   ```

## 모드 A: 가짜 토스

local 프로필 기본값이라 따로 켤 것이 없다. Infra/local의 `toss-mock`이 떠 있어야 하고, 꺼져 있으면 승인은 503 `PG_UNAVAILABLE`이다.

`payment.http`의 `@ref`·`@pgOrderId`·`@paymentKey` 등을 표대로 바꿔 요청을 보낸다. 기본값(`@pgOrderId = {{ref}}-1`, 금액 30000, 구매자 `@buyer`)과 다른 값만 적었다. stub 응답의 `orderId`가 표의 `pgOrderId`로 고정돼 있어 `@ref`를 바꾸면 S1~S5가 불일치로 바뀐다.

| 시나리오 | 순서 (`payment.http` 번호) | 기대 |
|---|---|---|
| S1 정상·멱등 | `@ref = OD-LOCAL-OK`, `@paymentKey = pk-ok`: 1 → 4 → 4 | 201 → 200 APPROVED 두 번. `pg_call` 1행, 분개 1건 |
| S2 거절 → 새 결제창 | `@ref = OD-LOCAL-DECLINE`, `pk-decline`: 1 → 4 → 4 → 3 → `@pgOrderId = {{ref}}-2`, `pk-retry`: 4 | 422 DECLINED → 409 STALE_CHECKOUT → 201 `-2` → 200 APPROVED |
| S3 토스 지연 | `@ref = OD-LOCAL-SLOW`, `pk-slow`: 1 → 4 → 몇 초 뒤 2 | 약 8초에 202 PENDING → APPROVED (stub이 12초 늦게 답한다) |
| S4 결과 불명 | `@ref = OD-LOCAL-UNKNOWN`, `pk-5xx`: 1 → 4 → 15초쯤 뒤 2 | 202 → UNKNOWN에서 조회로 APPROVED. `pg_call` CONFIRM·QUERY 2행 |
| S5 응답 불일치 | `@ref = OD-LOCAL-MISMATCH`, `pk-mismatch`: 1 → 4 | 409 PAYMENT_CLOSED → CANCELED. `pg_call` CONFIRM·CANCEL, 분개 없음 |
| S6 검증은 토스 전에 | `@ref = OD-LOCAL-VALID`, `pk-ok`: 1 → 4를 세 번 (`@pgOrderId = OTHER-1` / `@amount = 1` / `@approveBuyer = 00000000-0000-4000-8000-000000000000`), 마지막에 토큰 없는 5 | 400 ORDER_ID_MISMATCH · 400 AMOUNT_MISMATCH · 403 BUYER_MISMATCH · 401. `pg_call` 0행 |
| S7 만료 | `@ref = OD-LOCAL-EXPIRE`, 1번 스크립트의 `minutes = 1`: 1 → 2분 뒤 2 → 3 | CANCELED → 409 PAYMENT_CLOSED (만료 검사는 1분 간격) |

같은 시나리오를 다시 하려면 아래 초기화 SQL을 실행한다.

## 모드 B: 실제 토스 테스트 키

앱을 `--spring.profiles.active=local,toss-real`로 띄운다. 키는 토스 개발자센터 > API 키 > **API 개별 연동 키**의 한 쌍을 쓴다. 시크릿 키는 `test_sk_`, 결제창 키는 같은 쌍의 `test_ck_`다. 결제위젯 키(`test_gck_`·`test_gsk_`)는 이 결제창에서 쓰지 못한다.

1. `payment.http`에서 `@ref = OD-LOCAL-REAL`로 1(결제 생성)을 보낸다. 생성 전에 승인하면 404다.
2. `services/payment-service/local/toss-sandbox/serve.sh`를 실행하고 `http://localhost:8095/checkout.html?orderId=OD-LOCAL-REAL-1`을 열어 테스트 카드로 결제한다.
3. 성공 페이지가 보여 주는 `@pgOrderId`·`@paymentKey`·`@amount`를 `payment.http`에 넣고 4(승인)를 보낸다 → 200 APPROVED.
4. 4를 다시 보낸다 → 200 APPROVED, `pg_call` 행이 늘지 않는다.
5. 3(결제창 다시 열기)을 보낸다 → 409(이미 승인).

취소·환불 API는 1차 범위 밖이다. 테스트 결제 취소는 개발자센터에서 한다.

## 자주 걸리는 함정

- 시크릿 키를 결제위젯 키(`test_gsk_`)로 넣으면 토스가 키 오류로 답한다. API 개별 연동 키의 `test_sk_`를 쓴다.
- 결제 생성(1) 전에 승인(4)을 보내면 404다.
- 예전 `.env`에 `PAYMENT_TOSS_BASE_URL=http://localhost:18080`이 남아 있으면 `toss-real`에서 그 주소로 가 모든 호출이 NotSent(503)다. 그 줄을 지운다. local 프로필에서는 읽지 않는다.
- 결제창을 다시 열거나(3) 토스 호출이 NotSent로 끝난 뒤에는 `pgOrderId` 회차가 바뀐다. 이전 결제창에서 받은 인증은 쓰지 못하고, 새 회차로 결제창을 다시 연다.
- 시나리오 stub(`pk-*`)은 `-1` 회차 orderId 기준이다(S2 `pk-retry`만 `-2`). 회차가 바뀐 채 승인하면 응답 불일치로 보상 취소가 일어난다. 그 밖의 paymentKey는 요청한 orderId·금액으로 승인한다.
- 결제창 인증 후 승인은 제한 시간(토스 문서 기준 10분) 안에 보낸다.

## DB 확인·초기화

```bash
docker exec -it shoppinglive-postgres psql -U <POSTGRES_USER> -d payment
```

```sql
-- 결제·토스 호출·분개 (OD-LOCAL-OK 자리에 orderRef)
SELECT id, status, pg_order_id, checkout_seq, pg_payment_key, pg_status, next_check_at FROM payment WHERE order_ref = 'OD-LOCAL-OK';
SELECT kind, idempotency_key, outcome, http_status, result_code FROM pg_call
 WHERE payment_id = (SELECT id FROM payment WHERE order_ref = 'OD-LOCAL-OK') ORDER BY id;
SELECT j.id, e.account, e.debit, e.credit, e.seller_id FROM ledger_journal j JOIN ledger_entry e ON e.journal_id = j.id
 WHERE j.ref_id = (SELECT id FROM payment WHERE order_ref = 'OD-LOCAL-OK')::text ORDER BY e.id;
-- 차변 합 = 대변 합
SELECT sum(debit), sum(credit) FROM ledger_entry;
-- 초기화(로컬 payment DB 전용)
TRUNCATE payment, payment_seller_amount, pg_call, ledger_journal, ledger_entry, event_outbox RESTART IDENTITY CASCADE;
```

가짜 토스 요청 기록은 `curl -X DELETE localhost:59080/__admin/requests`로 지운다.

## 실제 토스 분류 확인 테스트(선택 실행)

브라우저 없이 실제 토스(`https://api.tosspayments.com`) 응답이 `TossPaymentsClient` 분류와 맞는지 본다. `toss-sandbox` 태그라 `test`·`check`·CI에서는 돌지 않는다.

```bash
(set -a; . services/payment-service/.env; set +a; ./gradlew :services:payment-service:tossSandboxTest)
```

- 틀린 시크릿 키 승인 → NotSent, HTTP 401 (키 없이도 실행된다)
- 테스트 시크릿 키 + 없는 paymentKey 승인 → Declined
- 테스트 시크릿 키 + 없는 paymentKey 조회 → Declined

`PAYMENT_TOSS_SECRET_KEY`가 `test_sk_`로 시작하지 않으면 뒤의 두 테스트는 스킵되고, 스킵이 있으면 작업이 실패한다. 키 없이 첫 테스트만 보려면 `--tests '*TossSandboxTest.wrongSecretKey*'`를 붙인다. 실제 토스 code·message는 테스트 출력에 찍힌다.
