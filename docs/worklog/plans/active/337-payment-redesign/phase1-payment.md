# Phase 1 결제 1차

상태: 구현·검증 완료, 병합 대기(2026-11-18). 상세 설계 확정은 2026-11-15
상위 계획: [#337 결제 서비스 재설계](README.md) · 요구사항: [결제](../../../../design-docs/services/payment/requirements/payment.md) · [원장](../../../../design-docs/services/payment/requirements/ledger.md)

이 문서는 Phase 1의 목차다. API·DB·이벤트·테스트·PR 나누기는 **이슈별 설계서**에 있다. 진행 방식과 PR 게이트는 [상위 계획](README.md#진행-방식-sdd--tdd)을 따른다.

## PR 스택

```text
dev ─ PR0 삭제 ─ PR1 스키마 ─ PR2 생성·조회 API ─ PR3 원장 ─┬─ PR5 승인 처리 ─ PR6 승인 API·8초 ─ PR7 복구·만료
dev ─ PR4 토스 클라이언트 (병렬 가능, PR5 전에 dev 병합) ───┘
```

## 이슈별 설계서

구현 세션은 맡은 이슈의 설계서 한 장만 읽고 시작한다. 설계서가 원본이고, 구현하며 바뀌면 설계서를 먼저 고친다.

| 순서 | 이슈 | 설계서 | PR | 핵심 |
|---|---|---|---|---|
| 1 | #347 기존 코드 삭제 | [347-legacy-removal.md](phase1/347-legacy-removal.md) | PR0 | 삭제 목록. #343을 기다리지 않음 |
| 2 | #348 생성·결제창·조회 | [348-create-api.md](phase1/348-create-api.md) | PR1 스키마 · PR2 API | **V4 스키마 원본**, 결제 키 규칙 |
| 3 | #338 승인·원장 | [338-approval-ledger.md](phase1/338-approval-ledger.md) | PR3 원장 · PR4 토스 · PR5 승인 처리 · PR6 승인 API | 토스 결과 분류, 승인 3단계, `PaymentApproved` |
| 4 | #349 복구·만료 | [349-recovery-expiry.md](phase1/349-recovery-expiry.md) | PR7 | 스케줄러 3개 |

설계 리뷰 지적과 반영 내역: [design-review.md](phase1/design-review.md)

PR 체크(구현 진행):

- [x] PR0 삭제 #352 · [x] PR1 스키마 #354 · [x] PR2 생성 API #355 · [x] PR3 원장 #356 · [x] PR4 토스 클라이언트 #353 · [x] PR5 승인 처리 #357 · [x] PR6 승인 API #358 · [x] PR7 복구·만료 #359
- 추가: 토스 샌드박스 확인 도구 #366(`services/payment-service/local/`, `tossSandboxTest`)
- 체크는 구현·CI 통과를 뜻한다. 병합은 [상위 계획의 스택 PR 순서](README.md#스택-pr)대로 한다.

## 종료 검증

1. payment-service `test`·`integrationTest`·`postgresTest`·`migrationTest` 통과. `scripts/check.sh --base origin/dev --external` 결과에 스킵·0건이 없다.
2. postgres 시나리오 테스트(토스는 `MockRestServiceServer`): 생성 → 승인 → APPROVED + 분개 1건 + outbox 1행. 거절 → 다시 결제 → 승인, UNKNOWN → 조회 DONE도 한 번씩.
3. 모든 분개의 차변 합 = 대변 합.
4. `requirements/payment.md`·`ledger.md`를 `완료`로 바꾸고 근거로 워크로그를 연결한다.

결과(근거: [#349 워크로그](../../../2026-11-13.md#349-결제-결과-복구와-만료-스케줄러-추가)의 실행 결과와 각 PR CI. 2026-11-18에 다시 실행하지 않았다):

| 항목 | 결과 |
|---|---|
| 1 | Payment `test`·`integrationTest`·`postgresTest`·`migrationTest`는 통과, 스킵 0(#358 기준 실행). `--base origin/dev --external` 전체 결과는 Payment와 무관한 CommerceCore Kafka 2건·notification 3건 스킵 때문에 incomplete다. PR #352–#359 CI 통과 |
| 2 | `PaymentScenarioPostgresTest`가 생성 → 승인, 거절 → 다시 결제 → 승인, UNKNOWN → 조회 DONE을 확인한다 |
| 3 | 같은 테스트가 분개별·전체 차변 합 = 대변 합을 확인한다 |
| 4 | `payment.md`는 `완료`. `ledger.md`는 Phase 3 범위인 LED-REFUND-01이 남아 `진행 중`으로 둔다 |

추가로 실제 토스 테스트 키로 승인 200·잘못된 키 401·없는 paymentKey 404를 확인했다(#366, [워크로그](../../../2026-11-13.md#338-토스-샌드박스-확인-도구와-실제-키-테스트)).
