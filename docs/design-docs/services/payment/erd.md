# Payment DB ERD

<!-- visual: relations -->

기준: #348 결제 스키마(V4)와 #385 환불 접수(V5). 컬럼 의미·예시와 스키마 원본은 [결제 생성·조회 설계](../../../worklog/plans/active/337-payment-redesign/phase1/348-create-api.md#db-v4__payment_coresql)에 있다. 새 설계 전체는 [Payment 도메인 홈](overview.md)에 있다.

[시각 자료](erd.html)

V4는 V1의 Mock 결제 테이블 4개(`payment_attempt`·`mock_gateway_result`·`refund_execution`·`mock_refund_result`)를 지우고 아래 테이블을 만든다. 마이그레이션 적용과 제약은 임시 PostgreSQL 16에서 `PaymentSchemaMigrationTest`로 확인했다. 운영 DB에 적용된 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| payment | PK id; UQ order_ref(6–50자), pg_order_id(지금 결제창 회차 `{orderRef}-{checkout_seq}`), pg_payment_key; buyer_id; amount > 0; refunded_amount 0–amount; status 7개; expires_at; pg_provider; checkout_seq; pg_status(마지막 토스 상태); partial_cancelable; approved_at; approving_at(정체 판단); next_check_at·check_deadline_at(불명 조회 예약·마감); created_at/updated_at | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| payment_seller_amount | PK id; FK payment_id; UQ seller_order_number; seller_id; amount > 0; commission_rate 0–1 | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| pg_call | PK id; FK payment_id; kind CONFIRM·QUERY·CANCEL; pg_order_id; idempotency_key; outcome SUCCESS·DECLINED·NOT_SENT·UNKNOWN(호출 전 UNKNOWN으로 넣고 응답 뒤 채움); http_status; result_code(토스 오류 코드); requested_at/responded_at | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| ledger_journal | PK id; ref_type PAYMENT_APPROVED·REFUND_SUCCEEDED·PURCHASE_CONFIRMED·SETTLEMENT_REVERSED·PAYOUT_COMPLETED(지금 코드는 PAYMENT_APPROVED만 쓴다); ref_id; UQ (ref_type, ref_id); created_at | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| ledger_entry | PK id; FK journal_id; account PG_CUSTODY·BUYER_DEPOSIT·SELLER_PAYABLE·COMMISSION_REVENUE; debit/credit 한쪽만 > 0; seller_id(SELLER_PAYABLE이면 필수) | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql) |
| refund | PK id; UQ refund_request_id(Commerce 요청 ID); UQ execution_id(결과의 executionId, UUID); FK payment_id(결제를 찾지 못한 FAILED는 NULL); purpose USER_REFUND·SYSTEM_COMPENSATION; amount ≥ 0; request_payload(받은 요청 원문); status PROCESSING·UNKNOWN·SUCCEEDED·FAILED·NO_CHARGE; failure_code; result_version(0이면 보낸 결과 없음); result_payload(마지막으로 보낸 결과 원문); created_at/updated_at | [V5](../../../../services/payment-service/src/main/resources/db/payment-migration/V5__refund.sql) |
| refund_request_conflict | PK id; refund_request_id; failure_code REQUEST_CONFLICT; request_payload(다르게 온 본문); created_at/updated_at | [V5](../../../../services/payment-service/src/main/resources/db/payment-migration/V5__refund.sql) |
| event_outbox | PK id; UQ event_id; payload; published_at; claim_token/lease_until; next_attempt_at | [V2](../../../../services/payment-service/src/main/resources/db/payment-migration/V2__event_outbox.sql) · [V3](../../../../services/payment-service/src/main/resources/db/payment-migration/V3__outbox_worker_claim.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| payment_seller_amount.payment_id | payment.id | 물리 FK · 결제 안의 판매자별 금액 |
| pg_call.payment_id | payment.id | 물리 FK · 결제의 PG 호출 기록 |
| ledger_entry.journal_id | ledger_journal.id | 물리 FK · 분개 줄 |
| refund.payment_id | payment.id | 물리 FK · 결제의 환불·보상 요청(결제 없음 FAILED는 NULL) |
| refund_request_conflict.refund_request_id | refund.refund_request_id | 값 · FK 없음 · 같은 요청 ID의 다른 본문 |
| payment.order_ref | Commerce 주문 번호 | 서비스 간 값 · FK 없음 |
| ledger_journal.ref_id | payment.id 등 사건 ID | ref_type별 값 · FK 없음 |

## 제약

- 상태·금액 규칙은 DB CHECK가 막는다: `ck_payment_status`(7개 상태), `ck_payment_refunded`(0 ≤ refunded_amount ≤ amount), `ck_ledger_side`(차변·대변 한쪽만 양수), `ck_ledger_seller`(판매자 미지급금 줄에 판매자 ID).
- 환불: `ck_refund_result`(result_version이 0이면 result_payload가 NULL). 결제당 진행 중 시스템 보상 1건은 부분 UNIQUE `uq_refund_active_compensation`(SYSTEM_COMPENSATION이고 PROCESSING·UNKNOWN). 접수는 `INSERT … ON CONFLICT DO NOTHING`(충돌 대상 없음)으로 요청 ID와 이 인덱스를 함께 받는다.
- 같은 사건의 분개는 `uq_ledger_journal_ref`로 1번만 남는다. 중복 감지는 `ON CONFLICT DO NOTHING`의 영향 행 수로 한다.
- 부분 인덱스: `ix_payment_check`(UNKNOWN의 next_check_at), `ix_payment_approving`(APPROVING의 approving_at), `ix_payment_expiry`(CREATED의 expires_at).
- 일반 인덱스: `ix_psa_payment`(payment_id), `ix_pg_call_payment`(payment_id, id), `ix_ledger_entry_journal`(journal_id), `ix_ledger_entry_account`(account, seller_id).
- 상태 변경은 이전 상태 조건부 UPDATE로만 한다. 엔티티에는 상태 setter가 없다.

## 원본과 매핑

- [V1__payment_baseline.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql)(V4가 지운 Mock 테이블)
- [V2__event_outbox.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V2__event_outbox.sql)
- [V3__outbox_worker_claim.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V3__outbox_worker_claim.sql)
- [V4__payment_core.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__payment_core.sql)
- [V5__refund.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V5__refund.sql)

JPA 매핑: [payment/entity](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/payment/entity/)(`Payment`·`PaymentSellerAmount`·`PgCall`), [ledger/entity](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/ledger/entity/)(`LedgerJournal`·`LedgerEntry`). `ddl-auto: validate`라 매핑이 DDL과 다르면 기동이 실패한다. `event_outbox`는 common-module `OutboxMessage`(JPA)와 `OutboxLeaseStore`(JDBC)가 쓴다.

상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
