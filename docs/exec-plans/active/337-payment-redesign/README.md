# #337 결제 서비스 재설계

상태: 진행 중 — 스펙 문서 PR 리뷰
관련 이슈: [#337 재설계](https://github.com/2026-2-rising-sun/Backend/issues/337)(상위). 하위 이슈는 로드맵 표에 있다.
관련 요구사항: [결제](../../../requirements/payment.md) · [원장](../../../requirements/ledger.md) · [환불](../../../requirements/refund.md)
관련 결정: [ADR 0002](../../../adr/0002-commerce-relay-internal-api.md) · [0003](../../../adr/0003-payment-replacement.md) · [0004](../../../adr/0004-payment-result-delivery.md) · [0005](../../../adr/0005-ledger-double-entry.md) · [0006](../../../adr/0006-pg-timeout-and-unknown.md)
단계 문서: [M1 결제 1차](m1-payment.md)
자료: [목표 설계와 작은 판단](decision.md) · [이벤트 스토밍 v41](event-storming-v41.html) (2026-11-13 고정 사본. 이후 원본은 요구사항 MD다)

## 목표와 범위

Mock 중심의 Payment를 토스 기준으로 교체한다. **최소 기능부터 완성한다**: 결제 1건이 끝까지 정확하게 끝나는 것이 먼저고, 대사·정산·지급은 후순위다. 기존 구현과 호환을 맞추지 않는다([ADR 0003](../../../adr/0003-payment-replacement.md)). 주문·재고·쿠폰은 CommerceCore 범위다.

## 로드맵

마일스톤은 요구사항 ID를 묶은 일정이다. 스펙 본문은 요구사항 문서에만 쓴다. M·PR 번호는 이 계획 안에서만 쓰는 이름이다. 요구사항·ADR·AGENTS·이슈·워크로그에는 쓰지 않고 이슈 번호나 내용으로 가리킨다.

| 단계 | 목표 | 이슈 | 요구사항 | 상태 |
|---|---|---|---|---|
| S | 스펙·결정·계획 문서 | #337 (PR #339) | 이 폴더, 요구사항 3개, ADR 0002–0006 | 리뷰 중 |
| **M1 결제 1차** | 기존 코드 삭제 | 생성 예정 | — | 대기 |
| | 결제 생성·결제창·조회 API | 생성 예정 | PAY-DATA·CREATE·CHECKOUT·QUERY·AUTH | 대기 |
| | 토스 승인과 원장 분개 | #338 | PAY-CONFIRM·PG·EVENT, LED-01–05·APPROVE-01 | 대기 |
| | 결과 불명 복구와 만료 | 생성 예정 | PAY-RECOVER·EXPIRE | 대기 |
| **M2 주문 연동** | Commerce가 새 결제 API·이벤트를 쓴다 | 착수 때 | 착수 때 Commerce 요구사항 작성. #331 담당과 범위 조율 | 대기 |
| **M3 환불** | 환불 접수·실행·늦은 승인 환불 + 환불 주문 연동 | 착수 때 | REF-*, LED-REFUND-01 | 대기 |
| 후순위 | 대사 → 정산 → 지급 → 선택(서킷 브레이커·2차 PG·웹훅) | 착수 때 | 착수 때 `requirements/reconciliation.md` 등 | 대기 |

M1 하위 이슈는 #337의 GitHub 하위 이슈로 연결한다. 이슈 하나 = 확인 가능한 기능 하나(PR 1–4개). PR은 자기 이슈를 `Refs`로 연결하고 그 이슈의 마지막 PR에서 `Closes`한다.

### 단계별 구체화

- 이 README는 전체 로드맵과 진행 방식만 가진다. PR 블록·종료 검증은 단계마다 `m<n>-<주제>.md` 한 파일에 쓴다.
- 단계를 시작할 때 그 단계 문서와 요구사항을 구체화하는 **문서 PR을 먼저** 올린다(작으면 첫 구현 PR에 넣는다). M1은 #339가 그 역할을 하고 [m1-payment.md](m1-payment.md)가 그 결과다. 끝난 단계 문서는 지우지 않고 계획과 함께 `completed/`로 간다.
- 구현 PR 본문에는 그 PR의 요구사항 ID와 동작, 구현 방식, 아키텍처·성능 변경점, 학습 포인트(핵심 문법 예시)를 쓴다.

### 후순위를 위해 지금 남기는 것

후순위 기능을 지금 구현하지 않는다. 다만 나중에 다시 만들지 않도록 M1·M3에서 데이터를 남긴다.

| 지금 남기는 것 | 나중에 쓰는 곳 |
|---|---|
| 승인·환불 분개(`ledger_*`) | 원장 대사, 판매자 잔액 |
| 판매자별 금액과 결제 시점 수수료율(`payment_seller_amount`) | 정산 금액 계산 |
| PG 호출 기록(`pg_call`) | 거래 대사, 결과 불명 조사 |
| 판매자 주문별 환불 줄(`refund_item`) | 정산 역정산 |

## 진행 방식 (SDD · TDD)

1. **스펙 먼저**: 단계 S의 PR을 리뷰·병합한다. ADR은 이때 `채택`이 된다. 다음 단계는 위 "단계별 구체화"대로 문서 PR로 시작하고 요구사항 상태를 `진행 중`으로 바꾼다.
2. **Red**: PR 블록의 요구사항 ID마다 실패하는 테스트를 쓴다. `@DisplayName`에 ID를 넣는다. 테스트만 먼저 커밋한다.
3. **Green**: 구현한다. 이때 테스트를 고치지 않는다. 테스트가 틀렸으면 멈추고 PR 본문에 이유를 적은 뒤 고친다.
4. **Refactor → 검증 → 독립 리뷰**: 아래 PR 게이트. 푸시는 Green 뒤에만 한다(자식 PR이 빨간 CI를 물려받지 않게).
5. **기록**: 요구사항 체크박스와 이 표의 상태, PR 블록 체크, [워크로그](../../../worklog/2026-11-13.md) 항목. 스펙이 바뀌면 요구사항 문서를 먼저 고친다.
   - 워크로그는 PR 하나에 항목 하나다(세션마다 쓰지 않는다). [팀 템플릿](../../../templates/worklog.md)의 제목 `## #<이슈> <PR 제목>`과 4항목(변경·판단 · 검증 · 미실행·한계 · 다음 작업)을 한 줄씩 쓰고 PR 링크를 단다. 계획 안의 M·PR 번호는 쓰지 않는다. 자세한 내용은 PR 본문과 CI가 가진다.
   - 현재 상태는 이 계획이 가진다. 워크로그는 다시 읽지 않는 근거 기록이고, 요구사항 `근거:`에서 항목 제목 앵커로만 연결한다.

사소한 변경(이름, 설정 한 줄)은 1·2를 건너뛴다.

### PR 게이트

1. 블록의 요구사항 ID마다 테스트가 있다. PR 본문 "검증"에 `ID → 테스트`를 적는다.
2. `scripts/check.sh --base <부모 브랜치 또는 origin/dev>` 통과. Payment postgres 테스트는 `PAYMENT_TEST_POSTGRES_URL/USER/PASSWORD`와 `--external`로 실행한다([도구](../../../guides/tooling.md)).
3. 블록의 "갱신할 문서"를 코드와 함께 고쳤다. `scripts/check-docs.sh`, `scripts/render-api-docs.sh --check` 통과.
4. CI 통과. size 라벨 `size/L` 이하. 넘으면 PR 본문에 이유 한 줄(`db/payment-migration` SQL과 삭제 줄도 크기에 들어간다).
5. 작성 세션이 아닌 세션의 독립 리뷰. 질문: ID마다 테스트가 실제로 그 조건을 검사하나 / 돈이 두 번 움직이지 않나 / 모든 상태 변경이 이전 상태 조건부 UPDATE이고 0행을 처리하나 / 트랜잭션 안에서 토스를 부르지 않나 / 범위 밖을 구현하지 않았나.

### 스택 PR

- PR base는 바로 앞 PR 브랜치, 첫 PR은 `dev`. 브랜치는 `<type>/#<그 PR의 하위 이슈>-<slug>`(PR 블록의 이슈).
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
읽을 것: docs/exec-plans/active/337-payment-redesign/m1-payment.md 의 "PR<n>" 블록, 블록이 가리키는 요구사항 ID, 필요하면 decision.md
저장소·base: Backend, base=<브랜치>, 새 브랜치=<브랜치>
끝: PR 게이트 결과(검사 출력 요약, ID→테스트 표)를 보고한다. 커밋·푸시·PR 생성은 지시가 있을 때만.
```

## M2 이후 (착수 때 문서 PR로 구체화한다)

| 단계 | PR (예상) |
|---|---|
| M2 주문 연동 | 착수 때 Commerce 쪽 설계. 결제 생성 호출·재시도, 승인 중계와 "결제 확인 중" 잠금, `PaymentApproved` 소비, 정체 해소 조회(원본 `#commerce`·`#checkout`). 끝에 토스 테스트 키로 결제위젯 → Commerce → Payment 수동 확인 |
| M3 환불 | R1 V5 + 접수 API(REF-ACCEPT-*) → R2 실행·결과 이벤트(REF-EXEC-01–03, REF-EVENT-01, LED-REFUND-01) → R3 복구·늦은 승인 소비(REF-EXEC-04, REF-LATE-*) → Commerce 환불 연동 |
| 후순위 | 대사: V6 + 원장 대사 → 거래 대사·자동 보정(EC 38). 정산: V7 + `PurchaseConfirmed` → NORMAL → REVERSAL. 지급: V8 + Mock 지급. 선택: 서킷 브레이커 → 2차 PG → 웹훅 |

## Commerce 의존

| 필요한 것 | 담당 | 시점 |
|---|---|---|
| 새 생성·승인 API 호출, `PaymentApproved` 소비 | M2 주문 연동. 주문 결제 전이를 맡은 [#331](https://github.com/2026-2-rising-sun/Backend/issues/331) 담당과 조율 | M1 직후. M1 PR0 병합부터 M2 완료까지 통합 개발 환경의 결제는 동작하지 않는다 |
| `RefundRequested` 발행, 환불 API 호출 | M3 환불 주문 연동 | M3 |
| `PurchaseConfirmed` 발행 | #331 | 정산 착수 때 |

Payment 쪽 검증은 Commerce 없이 끝나게 만든다.

## 인계

- 완료: 이벤트 스토밍 v41, 요구사항 초안 3개, ADR 0002–0006 제안, 이 계획.
- 남은 일: M1 하위 이슈 생성·연결 → 스펙 문서 PR 리뷰·병합 → ADR 채택 → M1 시작(기존 코드 삭제·토스 클라이언트부터).
- 조율: [#343](https://github.com/2026-2-rising-sun/Backend/pull/343)(Order)이 기존 Payment 코드·`V4`·`contracts/api/payment-service.yaml`을 바꾼다. M1의 삭제·스키마 PR과 겹치므로 병합 순서와 마이그레이션 번호를 그 담당과 먼저 정한다.
- 폴더를 옮기거나 `completed/`로 보낼 때: 요구사항·ADR이 `event-storming-v41.html`을 가리키므로 링크를 함께 고치고 `scripts/check-docs.sh`로 확인한다.
