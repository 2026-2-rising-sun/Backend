# 결제 서비스 재설계

상태: 진행 중 — 스펙 문서 PR 리뷰
관련 이슈: [#337 재설계](https://github.com/2026-2-rising-sun/Backend/issues/337) · [#338 결제 생성·승인·복구](https://github.com/2026-2-rising-sun/Backend/issues/338)
관련 요구사항: [결제](../../../requirements/payment.md) · [원장](../../../requirements/ledger.md) · [환불](../../../requirements/refund.md)
관련 결정: [ADR 0002](../../../adr/0002-commerce-relay-internal-api.md) · [0003](../../../adr/0003-payment-replacement.md) · [0004](../../../adr/0004-payment-result-delivery.md) · [0005](../../../adr/0005-ledger-double-entry.md) · [0006](../../../adr/0006-pg-timeout-and-unknown.md)
자료: [목표 설계와 작은 판단](decision.md) · [이벤트 스토밍 v41](event-storming-v41.html) (2026-11-13 고정 사본. 이후 원본은 요구사항 MD다)

## 목표와 범위

Mock 중심의 Payment를 토스 기준 결제·환불·원장·대사·정산·지급으로 교체한다. 기존 구현과 호환을 맞추지 않는다([ADR 0003](../../../adr/0003-payment-replacement.md)). 주문·재고·쿠폰은 CommerceCore 범위다.

## 로드맵

마일스톤은 요구사항 ID를 묶은 일정이다. 스펙 본문은 요구사항 문서에만 쓴다. M·PR 번호는 이 계획 안에서만 쓰는 이름이다. 요구사항·ADR·AGENTS·이슈·워크로그에는 쓰지 않고 이슈 번호나 내용으로 가리킨다.

| 단계 | 목표 | 요구사항 | 상태 |
|---|---|---|---|
| S | 스펙·결정·계획 문서 PR | 이 폴더, 요구사항 3개, ADR 0002–0006 | 진행 중 |
| M1 | 기존 코드 삭제 + 결제 생성·승인·복구·만료 ([#338](https://github.com/2026-2-rising-sun/Backend/issues/338)) | PAY-*, LED-01–05, LED-APPROVE-01 | 대기 |
| M2 | 환불 접수·실행·늦은 승인 환불 | REF-*, LED-REFUND-01 | 대기 |
| M3 | 원장 대사 → 거래 대사 | 착수 때 `requirements/reconciliation.md` 작성 | 대기 |
| M4 | 판매자 정산(구매확정 → NORMAL·REVERSAL) | 착수 때 `requirements/settlement.md` | 대기 |
| M5 | 판매자 지급(Mock 게이트웨이) | 착수 때 `requirements/payout.md` | 대기 |
| M6 | 선택: 서킷 브레이커·2차 PG·웹훅 | 필요할 때 | 선택 |

GitHub에는 재설계 이슈 1개를 두고, 마일스톤마다 하위 이슈를 1개씩 연다. PR은 하위 이슈를 `Refs`로 연결하고 마지막 PR에서 `Closes`한다.

## 진행 방식 (SDD · TDD)

1. **스펙 먼저**: 단계 S의 PR을 리뷰·병합한다. ADR은 이때 `채택`이 된다. 마일스톤을 시작할 때 그 단계의 요구사항을 다듬고(없으면 새로 쓰고) 상태를 `진행 중`으로 바꾼다.
2. **Red**: PR 블록의 요구사항 ID마다 실패하는 테스트를 쓴다. `@DisplayName`에 ID를 넣는다. 테스트만 먼저 커밋한다.
3. **Green**: 구현한다. 이때 테스트를 고치지 않는다. 테스트가 틀렸으면 멈추고 PR 본문에 이유를 적은 뒤 고친다.
4. **Refactor → 검증 → 독립 리뷰**: 아래 PR 게이트. 푸시는 Green 뒤에만 한다(자식 PR이 빨간 CI를 물려받지 않게).
5. **기록**: 요구사항 체크박스와 이 표의 상태, PR 블록 체크, [워크로그](../../../worklog/2026-11-13.md) 항목. 스펙이 바뀌면 요구사항 문서를 먼저 고친다.
   - 워크로그는 PR 하나에 항목 하나다(세션마다 쓰지 않는다). 제목 `## #<이슈> <PR 제목>`(팀 워크로그 형식. 계획 안의 M·PR 번호는 쓰지 않는다), 3–5줄: 변경 · 검증(명령·결과) · 미실행 · PR 링크. 자세한 내용은 PR 본문과 CI가 가진다.
   - 현재 상태는 이 계획이 가진다. 워크로그는 다시 읽지 않는 근거 기록이고, 요구사항 `근거:`에서 항목 제목 앵커로만 연결한다.

사소한 변경(이름, 설정 한 줄)은 1·2를 건너뛴다.

### PR 게이트

1. 블록의 요구사항 ID마다 테스트가 있다. PR 본문 "검증"에 `ID → 테스트`를 적는다.
2. `scripts/check.sh --base <부모 브랜치 또는 origin/dev>` 통과. Payment postgres 테스트는 `PAYMENT_TEST_POSTGRES_URL/USER/PASSWORD`와 `--external`로 실행한다([도구](../../../guides/tooling.md)).
3. 블록의 "갱신할 문서"를 코드와 함께 고쳤다. `scripts/check-docs.sh`, `scripts/render-api-docs.sh --check` 통과.
4. CI 통과. size 라벨 `size/L` 이하. 넘으면 PR 본문에 이유 한 줄(`db/payment-migration` SQL과 삭제 줄도 크기에 들어간다).
5. 작성 세션이 아닌 세션의 독립 리뷰. 질문: ID마다 테스트가 실제로 그 조건을 검사하나 / 돈이 두 번 움직이지 않나 / 모든 상태 변경이 이전 상태 조건부 UPDATE이고 0행을 처리하나 / 트랜잭션 안에서 토스를 부르지 않나 / 범위 밖을 구현하지 않았나.

### 스택 PR

- PR base는 바로 앞 PR 브랜치, 첫 PR은 `dev`. 브랜치는 `feat/#<하위 이슈>-<slug>`.
- 앞 PR이 squash 병합되면 다음 PR을 옮긴다. base만 바꾸면 CI가 다시 돌지 않으므로 푸시한다.

```bash
git fetch origin && git rebase --onto origin/dev <앞-PR-브랜치의-병합-전-끝-커밋> <내-브랜치>
```

```bash
gh pr edit <내-PR> --base dev && git push --force-with-lease
```

### 다른 세션에 넘길 때

```text
작업: 결제 재설계 M1 PR<n> <제목>
읽을 것: docs/exec-plans/active/337-payment-redesign/README.md 의 "PR<n>" 블록, 블록이 가리키는 요구사항 ID, 필요하면 decision.md
저장소·base: Backend, base=<브랜치>, 새 브랜치=<브랜치>
끝: PR 게이트 결과(검사 출력 요약, ID→테스트 표)를 보고한다. 커밋·푸시·PR 생성은 지시가 있을 때만.
```

## M1 단계

```text
dev ─ PR0 삭제 ─ PR1 스키마 ─ PR2 생성·조회 API ─ PR3 원장 ─┬─ PR5 승인 처리 ─ PR6 승인 API·8초 ─ PR7 복구·만료
dev ─ PR4 토스 클라이언트 (병렬 가능, PR5 전에 dev 병합) ───┘
```

- [ ] **PR0 기존 결제 코드 삭제** `payment-legacy-removal` · base `dev` · 크기 XL 가능(삭제 줄)
  - 범위: payment-service의 기존 패키지·`PaymentScenarioController`·Mock 엔진·그 테스트, `contracts/api/payment-service.yaml`의 기존 경로. commercecore payment 모듈은 #334 범위라 건드리지 않는다.
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
- [ ] **PR3 원장** `payment-ledger` · base PR2 · S · LED-01, LED-03–05, LED-APPROVE-01
  - `ledger/service`. 중복은 `ON CONFLICT DO NOTHING` + 영향 행 수. 호출자 트랜잭션에 참여.
- [ ] **PR4 토스 클라이언트** `toss-client` · base `dev` · M · PAY-PG-01–04
  - `client/toss`. `MockRestServiceServer`. 타임아웃 분류는 예외 → 결과 매핑 단위 테스트로 충분하다. 설정 `TOSS_SECRET_KEY`.
- [ ] **PR5 승인 처리(서비스)** `payment-approval` · base PR3(PR4 병합 뒤 rebase) · L · PAY-CONFIRM-01–10, 13, PAY-EVENT-01–02
  - 컨트롤러 없음. 트랜잭션: ① 검증 + CREATED→APPROVING 커밋 → ② 트랜잭션 밖 토스 호출 + pg_call → ③ 결과 반영.
  - 이벤트: `contracts/events/payment-execution-results.yaml`을 새 `PaymentApproved`로 교체. 키·aggregateId = orderRef(문자열). 메시지마다 `examples`, 발행 객체 직렬화 테스트가 `build/contract-samples`를 남긴다. EC 30(재시작 후 발행)은 common-module outbox 테스트에 맡긴다.
  - 갱신할 문서: `design-docs/services/payment/states/payment.md`·`.html`.
- [ ] **PR6 승인 API · 8초 · 벌크헤드** `payment-confirm-api` · base PR5 · L · PAY-CONFIRM-11–12와 승인 HTTP 매핑
  - 결과 → HTTP(요구사항 API 표): 승인 200 · 처리 중 202 · 거절 422 · 없음 404 · `BUYER_MISMATCH` 403 · `AMOUNT_MISMATCH` 400 · `PAYMENT_CLOSED`·`STALE_CHECKOUT` 409 · `PG_UNAVAILABLE`·`PAYMENT_BUSY` 503.
  - PR5와 나눈 이유: 엔드포인트가 8초 대기 없이 먼저 배포되면 Payment가 Commerce 10초보다 오래 기다린다([ADR 0006](../../../adr/0006-pg-timeout-and-unknown.md)).
  - 갱신할 문서: `api.html`.
- [ ] **PR7 복구 · 만료** `payment-recovery` · base PR6 · M · PAY-RECOVER-01–05, PAY-EXPIRE-01
  - 스케줄러 3개(APPROVING 스위퍼, UNKNOWN 조회, CREATED 만료). 분산 락 없이 조건부 UPDATE로 중복을 막는다. DONE 처리는 PR5 함수를 그대로 쓴다.
  - 갱신할 문서: `states/payment.md`·`.html`.

### M1 종료 검증

1. payment-service `test`·`integrationTest`·`postgresTest`·`migrationTest` 통과. `scripts/check.sh --base origin/dev --external` 결과에 스킵·0건이 없다.
2. postgres 시나리오 테스트(토스는 `MockRestServiceServer`): 생성 → 승인 → APPROVED + 분개 1건 + outbox 1행. 거절 → 다시 결제 → 승인, UNKNOWN → 조회 DONE도 한 번씩.
3. 모든 분개의 차변 합 = 대변 합.
4. 선택(#334 이후): 토스 테스트 키로 결제위젯 → Commerce → Payment 수동 확인.
5. `requirements/payment.md`·`ledger.md`를 `완료`로 바꾸고 근거로 워크로그를 연결한다.

## M2 이후 (착수 때 PR 블록을 M1 수준으로 쓴다)

| 단계 | PR (예상) |
|---|---|
| M2 | R1 V5 + 접수 API(REF-ACCEPT-*) → R2 실행·결과 이벤트(REF-EXEC-01–03, REF-EVENT-01, LED-REFUND-01) → R3 복구·늦은 승인 소비(REF-EXEC-04, REF-LATE-*) |
| M3 | ① V6 + 원장 대사 ② 거래 대사 + 자동 보정(EC 38) |
| M4 | ① V7 정산 + `PurchaseConfirmed` 소비 → NORMAL ② 확정 뒤 환불 → REVERSAL |
| M5 | V8 + Mock 지급 게이트웨이 + `PayoutCompleted` |
| M6 | 서킷 브레이커 → 2차 PG → 웹훅, 각 1 PR |

## Commerce 의존

| 필요한 것 | 담당 | 시점 |
|---|---|---|
| 새 생성·승인 API 호출, `PaymentApproved` 소비 | [#334](https://github.com/2026-2-rising-sun/Backend/issues/334), [#331](https://github.com/2026-2-rising-sun/Backend/issues/331) | M1 PR0·PR6 병합 직후. 그 전까지 통합 개발 환경의 결제는 동작하지 않는다 |
| `RefundRequested` 발행, 환불 API 호출 | #334 | M2 |
| `PurchaseConfirmed` 발행 | #331 | M4 |

Payment 쪽 검증은 Commerce 없이 끝나게 만든다.

## 인계

- 완료: 이벤트 스토밍 v41, 요구사항 초안 3개, ADR 0002–0006 제안, 이 계획.
- 남은 일: 스펙 문서 PR 리뷰·병합 → ADR 채택 → M1 시작.
- 폴더를 옮기거나 `completed/`로 보낼 때: 요구사항·ADR이 `event-storming-v41.html`을 가리키므로 링크를 함께 고치고 `scripts/check-docs.sh`로 확인한다.
