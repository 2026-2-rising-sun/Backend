# Payment DB ERD

<!-- visual: relations -->

기준: `cc91c09b`와 #331의 Payment 종료/보상 구현 변경. 실제 PostgreSQL / Mock gateway 검증을 수행했으며 외부 실제 PG·운영 적용 증거는 아니다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| payment_attempt | PK id; UQ request_id, payment_id; order_id; payment_group_id; amount; status; retry_count; lease_token/lease_until | [V1](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql) |
| mock_gateway_result | PK/FK attempt_id; outcome; authorized_at | [V1](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql) |
| refund_execution | PK id; UQ request_id, core_refund_request_id; payment_id; refund_amount; status; next_action_at; lease_token/lease_until | [V1](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql) |
| mock_refund_result | PK/FK refund_request_id; UQ refund_reference; outcome | [V1](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql) |
| event_outbox | PK id; UQ event_id; payload; published_at; claim_token/lease_until; next_attempt_at | [V2](../../../../services/payment-service/src/main/resources/db/payment-migration/V2__event_outbox.sql) · [V3](../../../../services/payment-service/src/main/resources/db/payment-migration/V3__outbox_worker_claim.sql) |
| payment_order_control | PK payment_id; UQ payment_request_id, closure_request_id; 원결제·종료 원문·closed_at | [V4](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__order_closure_compensation.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| mock_gateway_result.attempt_id | payment_attempt.id | 물리 FK · Mock 제공자 결과 |
| refund_execution.payment_id | payment_attempt.payment_id | 물리 FK · 같은 Payment DB의 원결제 |
| mock_refund_result.refund_request_id | refund_execution.id | 물리 FK · Mock 환불 결과 |
| payment_attempt.order_id | Core.orders.id | 서비스 간 ID · FK 없음 |
| refund_execution.core_refund_request_id | Core.refund_request.id | 서비스 간 ID · 유일키는 Payment 내부에서 보장 |
| payment_order_control.payment_id | payment_attempt.payment_id | 논리 실행 키. 종료 선도착을 위해 물리 FK 없음; attempt PK와 구분 |
| refund_execution.order_closure_request_id | payment_order_control.closure_request_id | 검증된 보상 원인. 원결제별 보상 유일 인덱스, 다른 환불 ID로 중복 우회 불가 |

## 제약과 이관

retry_count는 0~3이며 lease_token과 lease_until의 NULL 여부가 같아야 한다. 식별자는 Core에서 전달받지만 서비스 간 FK는 없다. UNKNOWN 결과 조회와 실행 재시도 예산을 구분한다.

V4는 기존 결제/환불 원문·상태·진행 시각을 수정하지 않는다. 결제 생성과 종료 접수·호출 시작은 같은 제어 행을 먼저 잠근다. 보상 종료 참조는 일반 환불에서 NULL이며, 종료 미수신은 503 재시도 대기다.

## 원본과 매핑

- [V1__payment_baseline.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V1__payment_baseline.sql)
- [V2__event_outbox.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V2__event_outbox.sql)
- [V3__outbox_worker_claim.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V3__outbox_worker_claim.sql)
- [V4__order_closure_compensation.sql](../../../../services/payment-service/src/main/resources/db/payment-migration/V4__order_closure_compensation.sql)

JPA 매핑: [PaymentAttempt.java](../../../../services/payment-service/src/main/java/com/shoppinglive/payment/entity/PaymentAttempt.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
