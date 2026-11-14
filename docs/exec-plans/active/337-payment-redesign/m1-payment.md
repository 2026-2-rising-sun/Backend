# M1 결제 1차

상태: 대기
상위 계획: [#337 결제 서비스 재설계](README.md) · 요구사항: [결제](../../../requirements/payment.md) · [원장](../../../requirements/ledger.md)

하위 이슈는 생성 후 각 PR 블록에 적는다. 진행 방식과 PR 게이트는 [상위 계획](README.md#진행-방식-sdd--tdd)을 따른다.

## PR 스택

```text
dev ─ PR0 삭제 ─ PR1 스키마 ─ PR2 생성·조회 API ─ PR3 원장 ─┬─ PR5 승인 처리 ─ PR6 승인 API·8초 ─ PR7 복구·만료
dev ─ PR4 토스 클라이언트 (병렬 가능, PR5 전에 dev 병합) ───┘
```

- [ ] **PR0 기존 결제 코드 삭제** `refactor/…-payment-legacy-removal` · base `dev` · 크기 XL 가능(삭제 줄)
  - 범위: payment-service의 기존 패키지·`PaymentScenarioController`·Mock 엔진·그 테스트, `contracts/api/payment-service.yaml`의 기존 경로. CommerceCore 결제 모듈은 주문 연동 단계에서 바꾸므로 건드리지 않는다.
  - 남길 것: `PaymentServiceApplication`, V1–V3 파일, `event_outbox`.
  - 확인: 컨트롤러 없이 기동(컨텍스트 로드 테스트 1개), `scripts/check.sh --base origin/dev`. commercecore 테스트가 깨지면 그 부분만 남기고 PR 본문에 적는다.
  - 갱신할 문서: `design-docs/services/payment/*`(기존 상태·ERD 설명 제거, API HTML 재생성), `design-docs/order-payment.md`·`refund.md`의 Payment 부분, `requirements/order-payment.md`의 Payment 기준.
- [ ] **PR1 스키마** `payment-core-schema` · base PR0 · M · 요구사항 PAY-DATA-01–03, LED-02–03(제약)
  - V4 맨 앞에서 기존 테이블을 자식부터 지운다(`mock_gateway_result`, `mock_refund_result` → `refund_execution` → `payment_attempt`). 이어서 원본 `#ddl`의 V4(payment, payment_seller_amount, pg_call, ledger_journal, ledger_entry).
  - 엔티티·리포지토리: `payment/{entity,repository}`, `ledger/{entity,repository}`.
  - 테스트: 제약은 `@Tag("postgres")` + `@Tag("migration")`, 엔티티 왕복은 `postgres`만. 기존 결제 테이블이 없고 `event_outbox`는 있는지 확인.
  - 갱신할 문서: `design-docs/services/payment/erd.md`·`erd.html`.
- [ ] **PR2 생성·결제창·조회 API** `payment-create-api` · base PR1 · L · PAY-CREATE-01–06, PAY-CHECKOUT-01–02, PAY-QUERY-01, PAY-AUTH-01
  - 보안 필터 체인 `/v1/internal/**` + `X-Service-Token`. 계약 YAML을 같은 PR에서 바꾼다([계약](../../../guides/contracts.md)). 동시 생성은 postgres 테스트.
  - 갱신할 문서: `api.html` 재생성, `design-docs/services/payment/README.md`.
- [ ] **PR3 원장** #338 `payment-ledger` · base PR2 · S · LED-01, LED-03–05, LED-APPROVE-01
  - `ledger/service`. 중복은 `ON CONFLICT DO NOTHING` + 영향 행 수. 호출자 트랜잭션에 참여.
- [ ] **PR4 토스 클라이언트** #338 `toss-client` · base `dev` · M · PAY-PG-01–04
  - `client/toss`. `MockRestServiceServer`. 타임아웃 분류는 예외 → 결과 매핑 단위 테스트로 충분하다. 시크릿 키 등 설정 이름은 구현하면서 정한다.
- [ ] **PR5 승인 처리(서비스)** #338 `payment-approval` · base PR3(PR4 병합 뒤 rebase) · L · PAY-CONFIRM-01–10, 12, PAY-EVENT-01–02
  - 컨트롤러 없음. 트랜잭션: ① 검증 + CREATED→APPROVING 커밋 → ② 트랜잭션 밖 토스 호출 + pg_call → ③ 결과 반영.
  - 이벤트: `contracts/events/payment-execution-results.yaml`을 새 `PaymentApproved`로 교체. 키·aggregateId = orderRef(문자열). 메시지마다 `examples`, 발행 객체 직렬화 테스트가 `build/contract-samples`를 남긴다. EC 30(재시작 후 발행)은 common-module outbox 테스트에 맡긴다.
  - 갱신할 문서: `design-docs/services/payment/states/payment.md`·`.html`.
- [ ] **PR6 승인 API · 8초** #338 `payment-confirm-api` · base PR5 · M · PAY-CONFIRM-11과 승인 HTTP 매핑
  - 결과 → HTTP(요구사항 API 표): 승인 200 · 처리 중 202(`Location` 헤더) · 거절 422 · 없음 404 · `ORDER_ID_MISMATCH`·`AMOUNT_MISMATCH` 400 · `BUYER_MISMATCH` 403 · `PAYMENT_CLOSED`·`STALE_CHECKOUT` 409 · `PG_UNAVAILABLE` 503.
  - PR5와 나눈 이유: 엔드포인트가 8초 대기 없이 먼저 배포되면 Payment가 Commerce 10초보다 오래 기다린다([ADR 0337-5](../../../adr/0337-5-pg-timeout-and-unknown.md)).
  - 갱신할 문서: `api.html`.
- [ ] **PR7 복구 · 만료** `payment-recovery` · base PR6 · M · PAY-RECOVER-01–05, PAY-EXPIRE-01
  - 스케줄러 3개(APPROVING 스위퍼, UNKNOWN 조회, CREATED 만료). 분산 락 없이 조건부 UPDATE로 중복을 막는다. DONE 처리는 PR5 함수를 그대로 쓴다.
  - 갱신할 문서: `states/payment.md`·`.html`.

## 종료 검증

1. payment-service `test`·`integrationTest`·`postgresTest`·`migrationTest` 통과. `scripts/check.sh --base origin/dev --external` 결과에 스킵·0건이 없다.
2. postgres 시나리오 테스트(토스는 `MockRestServiceServer`): 생성 → 승인 → APPROVED + 분개 1건 + outbox 1행. 거절 → 다시 결제 → 승인, UNKNOWN → 조회 DONE도 한 번씩.
3. 모든 분개의 차변 합 = 대변 합.
4. `requirements/payment.md`·`ledger.md`를 `완료`로 바꾸고 근거로 워크로그를 연결한다.
