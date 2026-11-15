# Payment DB ERD

<!-- visual: relations -->

기준: #348 결제 스키마(V4). 컬럼 의미·예시와 스키마 원본은 [결제 생성·조회 설계](../../../exec-plans/active/337-payment-redesign/phase1/348-create-api.md#db-v4__payment_coresql)에 있다. 새 설계 전체는 [Payment 도메인 홈](README.md)에 있다.

[시각 자료](erd.html)

V4는 V1의 Mock 결제 테이블 4개(`payment_attempt`·`mock_gateway_result`·`refund_execution`·`mock_refund_result`)를 지우고 아래 테이블을 만든다. 마이그레이션 적용과 제약은 임시 PostgreSQL 16에서 `PaymentSchemaMigrationTest`로 확인했다. 운영 DB에 적용된 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| payment | PK id; UQ order_ref(6–50자), pg_order_id, pg_payment_key; buyer_id; amount > 0; refunded_amount 0–amount; status 7개; expires_at; checkout_seq | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| payment_seller_amount | PK id; FK payment_id; UQ seller_order_number; seller_id; amount > 0; commission_rate 0–1 | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| pg_call | PK id; FK payment_id; kind CONFIRM·QUERY·CANCEL; idempotency_key; outcome SUCCESS·DECLINED·NOT_SENT·UNKNOWN; requested_at/responded_at | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| ledger_journal | PK id; UQ (ref_type, ref_id); created_at | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| ledger_entry | PK id; FK journal_id; account 4개; debit/credit 한쪽만 > 0; seller_id(SELLER_PAYABLE이면 필수) | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| event_outbox | PK id; UQ event_id; payload; published_at; claim_token/lease_until; next_attempt_at | [V2](../../../../services/payment-service/src/main/resources/db/payment-migration/V2__event_outbox.sql) · [V3](../../../../services/payment-service/src/main/resources/db/payment-migration/V3__outbox_worker_claim.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| payment_seller_amount.payment_id | payment.id | 물리 FK · 결제 안의 판매자별 금액 |
| pg_call.payment_id | payment.id | 물리 FK · 결제의 PG 호출 기록 |
| ledger_entry.journal_id | ledger_journal.id | 물리 FK · 분개 줄 |
| payment.order_ref | Commerce 주문 번호 | 서비스 간 값 · FK 없음 |
| ledger_journal.ref_id | payment.id 등 사건 ID | ref_type별 값 · FK 없음 |

## 제약

- 상태·금액 규칙은 DB CHECK가 막는다: `ck_payment_status`(7개 상태), `ck_payment_refunded`(0 ≤ refunded_amount ≤ amount), `ck_ledger_side`(차변·대변 한쪽만 양수), `ck_ledger_seller`(판매자 미지급금 줄에 판매자 ID).
- 같은 사건의 분개는 `uq_ledger_journal_ref`로 1번만 남는다. 중복 감지는 `ON CONFLICT DO NOTHING`의 영향 행 수로 한다.
- 부분 인덱스: `ix_payment_check`(UNKNOWN의 next_check_at), `ix_payment_approving`(APPROVING의 approving_at), `ix_payment_expiry`(CREATED의 expires_at).
- 상태 변경은 이전 상태 조건부 UPDATE로만 한다. 엔티티에는 상태 setter가 없다.

## 원본과 매핑

- [V1__payment_baseline.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql)(V4가 지운 Mock 테이블)
- [V2__event_outbox.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V2__event_outbox.sql)
- [V3__outbox_worker_claim.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V3__outbox_worker_claim.sql)
- [V4__payment_core.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql)

JPA 매핑: [payment/entity](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/payment/entity/)(`Payment`·`PaymentSellerAmount`·`PgCall`), [ledger/entity](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/ledger/entity/)(`LedgerJournal`·`LedgerEntry`). `ddl-auto: validate`라 매핑이 DDL과 다르면 기동이 실패한다. `event_outbox`는 common-module `OutboxMessage`(JPA)와 `OutboxLeaseStore`(JDBC)가 쓴다.

상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
