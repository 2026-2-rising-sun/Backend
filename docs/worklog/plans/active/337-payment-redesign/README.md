# #337 결제 서비스 재설계

상태: 진행 중 — Phase 1 상세 설계 확정(2026-11-15 사용자 동의). 구현 착수 준비
관련 이슈: [#337 재설계](https://github.com/2026-2-rising-sun/Backend/issues/337)(상위). 하위 이슈는 로드맵 표에 있다.
관련 요구사항: [결제](../../../../design-docs/services/payment/requirements/payment.md) · [원장](../../../../design-docs/services/payment/requirements/ledger.md) · [환불](../../../../design-docs/services/payment/requirements/refund.md)
관련 결정: [ADR 0337-1](../../../../adr/0337-1-commerce-relay-internal-api.md) · [0337-2](../../../../adr/0337-2-payment-replacement.md) · [0337-3](../../../../adr/0337-3-payment-result-delivery.md) · [0337-4](../../../../adr/0337-4-ledger-double-entry.md) · [0337-5](../../../../adr/0337-5-pg-timeout-and-unknown.md)
Phase 문서: [Phase 1 결제 1차](phase1-payment.md)
자료: [목표 설계와 작은 판단](decision.md) · [Payment 도메인 홈](../../../../design-docs/services/payment/overview.md)(새 설계와 현재 코드 문서 목록) · [목표 설계](../../../../design-docs/services/payment/design.html)(그림·예시. 원본은 요구사항 MD와 ADR)

## 목표와 범위

Mock 중심의 Payment를 토스 기준으로 교체한다. **최소 기능부터 완성한다**: 결제 1건이 끝까지 정확하게 끝나는 것이 먼저고, 대사·정산·지급은 후순위다. 기존 구현과 호환을 맞추지 않는다([ADR 0337-2](../../../../adr/0337-2-payment-replacement.md)).

- **이 계획이 맡는 것**: Payment 전체(Phase 1·3·후순위)와 결제에 연관된 주문 흐름(Phase 2 주문 연동). 결제와 주문이 겹치는 설계는 이 계획이 우선한다.
- **도메인 분리**: Phase 2는 #337의 하위 이슈로 두되 `service:commercecore` 라벨, 별도 Phase 문서, 별도 세션으로 진행한다. 두 도메인은 계약(`contracts/`)과 ADR로만 잇는다.
- **CommerceCore 범위**: 재고·쿠폰, 주문 모듈 구조(#331).
- **범위 밖**(만들지 않음, 필요하면 별도 요구사항): 토스 호출 동시 실행 수 제한(벌크헤드)·웹훅·2차 PG·서킷 브레이커, 토스 지급대행 실연동·셀러 등록(지급은 Mock), 스케줄러 분산 락, Redis 등 경합 최적화.

## 로드맵

**Phase**는 이 계획을 나누는 작업 단위다(마일스톤·단계라고 부르지 않는다). Phase마다 요구사항 ID를 묶고, 끝났다는 기준은 Phase 문서의 "종료 검증"이다. 스펙 본문은 요구사항 문서에만 쓴다. `Phase n`·`PR<n>` 번호는 이 계획 안에서만 쓰는 이름이다. 요구사항·ADR·AGENTS·이슈·워크로그에는 쓰지 않고 이슈 번호나 내용으로 가리킨다.

| Phase | 목표 | 이슈 | 요구사항 | 상태 |
|---|---|---|---|---|
| 0 스펙 | 스펙·결정·계획 문서 | #337 (PR #339) | 이 폴더, 요구사항 3개, ADR 0337-1–5 | 완료 |
| **1 결제 1차** | 기존 코드 삭제 | #347 | — | 대기 |
| | 결제 생성·결제창·조회 API | #348 | PAY-DATA·CREATE·CHECKOUT·QUERY·AUTH | 대기 |
| | 토스 승인과 원장 분개 | #338 | PAY-CONFIRM·PG·EVENT, LED-01–05·APPROVE-01 | 대기 |
| | 결과 불명 복구와 만료 | #349 | PAY-RECOVER·EXPIRE | 대기 |
| **2 주문 연동** | Commerce가 새 결제 API·이벤트를 쓴다 | 착수 때 | 착수 때 Commerce 요구사항 작성. #331 담당과 범위 조율 | 대기 |
| **3 환불** | 환불 접수·실행·늦은 승인 환불 + 환불 주문 연동 | 착수 때 | REF-*, LED-REFUND-01 | 대기 |
| 후순위 | 대사 → 정산 → 지급 → 선택(서킷 브레이커·2차 PG·웹훅) | 착수 때 | 착수 때 `requirements/reconciliation.md` 등 | 대기 |

모든 Phase의 이슈는 #337의 GitHub 하위 이슈로 연결하고, #337 본문의 로드맵 체크리스트에 추가한다. 이슈 하나 = 확인 가능한 기능 하나(PR 1–4개). PR은 자기 이슈를 `Refs`로 연결하고 그 이슈의 마지막 PR에서 `Closes`한다.

### Phase별 구체화

- **큰 그림은 이 README와 #337 하나가 맡는다.** 모든 Phase의 목표·범위·순서·의존만 쓰고 세부는 쓰지 않는다. #337 본문의 로드맵 체크리스트는 이 표와 같이 갱신한다.
- **상세 설계는 Phase별로 쓴다.** API·DB·이벤트·PR 블록·종료 검증은 그 Phase를 시작할 때 `phase<n>-<주제>.md`(필요하면 이슈별 파일)에 쓰고, 구현하면서 바뀌면 고친다.
- Phase마다 하위 이슈를 만들고, 그 Phase 문서와 요구사항을 구체화하는 **문서 PR을 먼저** 올린다(작으면 첫 구현 PR에 넣는다). Phase 1은 #339가 그 역할을 하고 [phase1-payment.md](phase1-payment.md)가 그 결과다. 끝난 Phase 문서는 지우지 않고 계획과 함께 `completed/`로 간다.
- 구현 PR 본문에는 그 PR의 요구사항 ID와 동작, 구현 방식, 아키텍처·성능 변경점, 학습 포인트(핵심 문법 예시)를 쓴다.

### 후순위를 위해 지금 남기는 것

후순위 기능을 지금 구현하지 않는다. 다만 나중에 다시 만들지 않도록 Phase 1·3에서 데이터를 남긴다.

| 지금 남기는 것 | 나중에 쓰는 곳 |
|---|---|
| 승인·환불 분개(`ledger_*`) | 원장 대사, 판매자 잔액 |
| 판매자별 금액과 결제 시점 수수료율(`payment_seller_amount`) | 정산 금액 계산 |
| PG 호출 기록(`pg_call`) | 거래 대사, 결과 불명 조사 |
| 판매자 주문별 환불 줄(`refund_item`) | 정산 역정산 |

## 진행 방식 (SDD · TDD)

1. **스펙 먼저**: Phase 0의 PR을 리뷰·병합한다. ADR은 이때 `채택`이 된다. 다음 Phase는 위 "Phase별 구체화"대로 문서 PR로 시작하고 요구사항 상태를 `진행 중`으로 바꾼다.
2. **Red**: PR 블록의 요구사항 ID마다 실패하는 테스트를 쓴다. `@DisplayName`에 ID를 넣는다. 테스트만 먼저 커밋한다.
3. **Green**: 구현한다. 이때 테스트를 고치지 않는다. 테스트가 틀렸으면 멈추고 PR 본문에 이유를 적은 뒤 고친다.
4. **Refactor → 검증 → 독립 리뷰**: 아래 PR 게이트. 푸시는 Green 뒤에만 한다(자식 PR이 빨간 CI를 물려받지 않게).
5. **기록**: 요구사항 체크박스와 이 표의 상태, PR 블록 체크, [워크로그](../../../2026-11-13.md) 항목. 스펙이 바뀌면 요구사항 문서를 먼저 고친다.
   - 워크로그는 PR 하나에 항목 하나다(세션마다 쓰지 않는다). [팀 템플릿](../../../../templates/worklog.md)의 제목 `## #<이슈> <PR 제목>`과 4항목(변경·판단 · 검증 · 미실행·한계 · 다음 작업)을 한 줄씩 쓰고 PR 링크를 단다. 계획 안의 M·PR 번호는 쓰지 않는다. 자세한 내용은 PR 본문과 CI가 가진다.
   - 현재 상태는 이 계획이 가진다. 워크로그는 다시 읽지 않는 근거 기록이고, 요구사항 `근거:`에서 항목 제목 앵커로만 연결한다.

사소한 변경(이름, 설정 한 줄)은 1·2를 건너뛴다.

### PR 게이트

1. 블록의 요구사항 ID마다 테스트가 있다. PR 본문 "검증"에 `ID → 테스트`를 적는다.
2. `scripts/check.sh --base <부모 브랜치 또는 origin/dev>` 통과. Payment postgres 테스트는 `PAYMENT_TEST_POSTGRES_URL/USER/PASSWORD`와 `--external`로 실행한다([도구](../../../../guides/tooling.md)).
3. 블록의 "갱신할 문서"를 코드와 함께 고쳤다. 요구사항·API·상태를 바꾸면 [Payment 목표 설계](../../../../design-docs/services/payment/design.html)도 같은 PR에서 고친다. `scripts/check-docs.sh`, `scripts/render-api-docs.sh --check` 통과.
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
작업: 결제 재설계 Phase 1 PR<n> <제목>
읽을 것: docs/worklog/plans/active/337-payment-redesign/phase1/<이슈>-*.md 의 "PR 나누기"에서 PR<n> 줄, 설계서가 가리키는 요구사항 ID
저장소·base: Backend, base=<브랜치>, 새 브랜치=<브랜치>
끝: PR 게이트 결과(검사 출력 요약, ID→테스트 표)를 보고한다. 커밋·푸시·PR 생성은 지시가 있을 때만.
```

## Phase 2 이후 (큰 그림. 상세는 착수 때 Phase 문서 PR로 쓴다)

| Phase | PR (예상) |
|---|---|
| 2 주문 연동 | 착수 때 Commerce 쪽 설계. 결제 생성 호출·재시도, 승인 중계와 "결제 확인 중" 잠금, `PaymentApproved` 소비, 정체 해소 조회(원본 `#commerce`·`#checkout`). 기존 CommerceCore 결제 코드(재전달·소비자·만료 배치)를 고쳐 쓸지 교체할지 먼저 정한다(원본 `#commerce` 대응표). 끝에 토스 테스트 키로 결제위젯 → Commerce → Payment 수동 확인 |
| 3 환불 | R1 V5 + 접수 API(REF-ACCEPT-*) → R2 실행·결과 이벤트(REF-EXEC-01–03, REF-EVENT-01, LED-REFUND-01) → R3 복구·늦은 승인 소비(REF-EXEC-04, REF-LATE-*) → Commerce 환불 연동 |
| 후순위 | 대사: V6 + 원장 대사 → 거래 대사·자동 보정(EC 38). 정산: V7 + `PurchaseConfirmed` → NORMAL → REVERSAL. 지급: V8 + Mock 지급. 선택: 서킷 브레이커 → 2차 PG → 웹훅 |

## Commerce 의존

| 필요한 것 | 담당 | 시점 |
|---|---|---|
| 새 생성·승인 API 호출, `PaymentApproved` 소비 | Phase 2 주문 연동. [#331](https://github.com/2026-2-rising-sun/Backend/issues/331)의 Order 구조 위에서 진행하고, 겹치는 결제 흐름은 이 설계를 따른다 | Phase 1 직후. Phase 1 PR0 병합부터 Phase 2 완료까지 통합 개발 환경의 결제는 동작하지 않는다 |
| `RefundRequested` 발행, 환불 API 호출 | Phase 3 환불 주문 연동 | Phase 3 |
| `PurchaseConfirmed` 발행 | #331 | 정산 착수 때 |

Payment 쪽 검증은 Commerce 없이 끝나게 만든다.

## 인계

- 완료: Payment 목표 설계 HTML, 요구사항 초안 3개, ADR 0337-1–5 채택, 이 계획.
- 남은 일: 큰 그림 확정(이 README) → Phase 1 상세 설계(이슈별) → Phase 1 구현(기존 코드 삭제·토스 클라이언트부터).
- 조율: [#343](https://github.com/2026-2-rising-sun/Backend/pull/343)(Order)이 기존 Payment 코드·`V4`·`contracts/api/payment-service.yaml`을 바꾼다. 2026-11-15 그 PR에서 Payment 부분(종료 통지 API·`payment_order_control`·보상 환불 필드)을 빼 달라고 요청했다. 빠지면 Phase 1 스키마는 `V4`를 그대로 쓴다. 남은 채 병합되면 `V5`로 옮기고 그 테이블도 함께 지운다. 답을 기다리지 않고 기존 코드 삭제를 먼저 진행하며, 충돌은 그 PR이 맞춘다(2026-11-15 결정).
- 결제 재설계가 끝나면 `design-docs/services/payment/design.html` 상단의 "구현 전 목표" 안내를 지우고 모듈 문서의 현재 코드 설명과 맞춘다.
