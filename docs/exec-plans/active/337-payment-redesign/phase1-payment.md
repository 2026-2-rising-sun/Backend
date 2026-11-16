# Phase 1 결제 1차

상태: 상세 설계 확정(2026-11-15 사용자 동의). 구현 착수 준비
상위 계획: [#337 결제 서비스 재설계](README.md) · 요구사항: [결제](../../../requirements/payment.md) · [원장](../../../requirements/ledger.md)

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

- [ ] PR0 삭제 · [ ] PR1 스키마 · [ ] PR2 생성 API · [ ] PR3 원장 · [ ] PR4 토스 클라이언트 · [ ] PR5 승인 처리 · [ ] PR6 승인 API · [ ] PR7 복구·만료

## 종료 검증

1. payment-service `test`·`integrationTest`·`postgresTest`·`migrationTest` 통과. `scripts/check.sh --base origin/dev --external` 결과에 스킵·0건이 없다.
2. postgres 시나리오 테스트(토스는 `MockRestServiceServer`): 생성 → 승인 → APPROVED + 분개 1건 + outbox 1행. 거절 → 다시 결제 → 승인, UNKNOWN → 조회 DONE도 한 번씩.
3. 모든 분개의 차변 합 = 대변 합.
4. `requirements/payment.md`·`ledger.md`를 `완료`로 바꾸고 근거로 워크로그를 연결한다.
