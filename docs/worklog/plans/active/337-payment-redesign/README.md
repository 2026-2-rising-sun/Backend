# #337 결제 서비스 재설계

상태: 진행 중 — Backend 코드 작업(Phase 1~3)은 dev에 머지됐다. 남은 일은 Frontend #89, Infra #64 알림의 실제 확인, Backend #401·#387·#367의 완료 조건 확인이다.
관련 이슈: [#337 재설계](https://github.com/2026-2-rising-sun/Backend/issues/337)(상위). 남은 이슈는 [서비스별 표](#phase-23의-남은-일)에 있다.
관련 요구사항: [결제](../../../../design-docs/services/payment/requirements/payment.md) · [원장](../../../../design-docs/services/payment/requirements/ledger.md) · [환불](../../../../design-docs/services/payment/requirements/refund.md)
관련 결정: [ADR 0337-1](../../../../adr/0337-1-commerce-relay-internal-api.md) · [0337-2](../../../../adr/0337-2-payment-replacement.md) · [0337-3](../../../../adr/0337-3-payment-result-delivery.md) · [0337-4](../../../../adr/0337-4-ledger-double-entry.md) · [0337-5](../../../../adr/0337-5-pg-timeout-and-unknown.md)
Phase 1 구현 기록: [결제 1차](phase1-payment.md)
자료: [목표 설계와 작은 판단](decision.md) · [Payment 도메인 홈](../../../../design-docs/services/payment/overview.md)(새 설계와 현재 코드 문서 목록) · [목표 설계](../../../../design-docs/services/payment/design.html)(그림·예시. 원본은 요구사항 MD와 ADR)

## 처음 읽는 사람을 위한 요약

Payment를 Mock에서 실제 토스 결제로 교체하고, 주문 서비스(CommerceCore)와 연결하는 작업이다. **결제와 환불의 정상 흐름, 장애 때 돈과 주문이 어긋나지 않게 막는 처리, 옛 코드 삭제는 dev에 머지됐다.** 남은 일은 화면 연결과 알림·완료 조건의 실제 확인이다.

```text
결제: 구매자 → Frontend → CommerceCore → (HTTP) → Payment → 토스
      HTTP 응답을 잃어도 Payment → payment.execution.results.v1 → CommerceCore 로 결과가 도착한다

환불: 구매자 → Frontend → CommerceCore → commerce.refund.requests.v1 → Payment → 토스 취소
      Payment → payment.refund.results.v1 → CommerceCore
```

| 지금 남은 문제 | 고객이나 운영에 생기는 일 | 이슈 |
|---|---|---|
| 화면이 새 상태를 모른다 | 처리 중인 주문이 오류처럼 보일 수 있다 | Frontend #89 |
| DLT 유입·장기 미확정 알림을 실제로 받아 보지 않았다 | 처리 못 한 메시지나 묶인 고객 돈을 사람이 늦게 알 수 있다 | Infra #64, #387(지표는 PR #413로 머지) |
| 토스의 환불 진행 중 응답(`ALREADY_REFUNDING_PAYMENT`)을 실제로 재현하지 못했다 | 코드는 PR #406로 머지됐고 테스트로만 확인했다 | #401 |
| 구매자 일반 환불의 Core 완료 조건을 확인하지 않았다 | 토스 테스트 환경 E2E 근거는 이슈 댓글에 있다. 조건별 확인과 닫기만 남았다 | #367 |

해결됨: 승인 429의 이중 청구 위험(#398), Core 결제 결과 소비자의 무한 재시도(#389), 보상 대기 중 새 구매 차단(#377), 미확정 환불 중 다른 상품 환불(#399), 멈춘 환불의 수동 처리 절차(#369), 옛 결제 경로와 계약(#384·#390·#402), 일부 수량 환불(#345).

다음 Phase 후보:

- #416 CommerceCore: 결제 결과 불명 주문을 처리 기한까지 유지한다(목표 설계와 구현 차이).
- #417 CommerceCore: 결제 미확정으로 끝낸 주문의 재고·쿠폰 예약을 즉시 해제한다(목표 설계와 구현 차이).
- #418 CommerceCore: 승인 거절 뒤 만료 전까지 같은 주문으로 재결제한다(목표 설계와 구현 차이).
- #400 Payment: 서킷 브레이커와 Mock 2차 PG 라우팅.

### 용어

- **UNKNOWN(결과 불명)**: 토스에서 돈이 처리됐는지 아직 모르는 상태. 실패로 단정하지 않고 조회로 확인한다.
- **시스템 보상**: 주문은 끝났는데 결제됐을 수 있을 때, 결제 여부를 확인해 결제됐으면 자동으로 환불하는 처리.
- **DLT**: 소비자가 처리하지 못한 Kafka 메시지를 따로 모아 두는 토픽. 뒤 메시지가 막히지 않게 한다.
- **Outbox**: DB 변경과 보낼 이벤트를 한 트랜잭션에 저장해 이벤트 유실을 막는 방식.

### 팀별로 시작할 곳

| 팀 | 먼저 읽을 것 | 바로 시작할 수 있는 이슈 |
|---|---|---|
| Payment | [환불 요구사항](../../../../design-docs/services/payment/requirements/refund.md), [결제 요구사항](../../../../design-docs/services/payment/requirements/payment.md) | #401·#387 완료 조건 확인, 다음 후보 #400 |
| CommerceCore | 각 이슈 본문, [이벤트 계약](../../../../../contracts/events/commerce-refund-requests.yaml) | #367 완료 조건 확인, 다음 후보 #416·#417·#418 |
| Infra | [Infra #64](https://github.com/2026-2-rising-sun/Infra/issues/64) | DLT 토픽·보관·유입 알림과 미확정 알림의 실제 확인 |
| Frontend | [Frontend #89](https://github.com/2026-2-rising-sun/Frontend/issues/89) | Backend 코드는 머지됨. 바로 시작 |

로컬 실행은 [로컬 환경](../../../../design-docs/flows/local-environment.md)을 따른다.

### 이번 범위에서 정한 것

- 처리 못 한 메시지는 **그 메시지를 소비하는 서비스 팀**이 원인을 고치고 재처리한다. 판매자와 구매자에게 DLT를 노출하지 않는다.
- 전용 재처리 도구, 관리자 화면, 알림 등급은 만들지 않는다. Kafka offset 되감기, Kafka UI, 기존 Slack 알림, 운영 절차 문서로 처리한다.
- 공통화는 Kafka 소비 오류 처리기만 한다. 돈의 안전 경계가 숨겨지지 않도록 새 `@Aspect`는 만들지 않는다.
- 서킷 브레이커는 Payment가 판단한다. Core는 Payment가 고른 PG를 전달만 하고, 환불은 항상 원래 PG로 보낸다(#400).

## 기준 문서

같은 내용을 여러 곳에 복사하지 않는다. 아래 표의 기준 문서만 고치고 다른 곳에서는 링크한다.

| 알고 싶은 것 | 기준 |
|---|---|
| 범위, 서비스 소유, 작업 순서 | 이 문서 |
| 이슈별 동작과 완료 조건 | 각 GitHub 이슈 본문 |
| 이슈 진행 상태 | GitHub 이슈의 열림·닫힘과 [#337](https://github.com/2026-2-rising-sun/Backend/issues/337) 체크리스트 |
| 요구사항 | [결제](../../../../design-docs/services/payment/requirements/payment.md) · [원장](../../../../design-docs/services/payment/requirements/ledger.md) · [환불](../../../../design-docs/services/payment/requirements/refund.md) |
| API·이벤트 | `contracts/` |
| 되돌리기 어려운 결정 | `docs/adr/` |

## 목표와 범위

Mock 중심의 Payment를 토스 기준으로 교체한다. **최소 기능부터 완성한다**: 결제 1건이 끝까지 정확하게 끝나는 것이 먼저고, 대사·정산·지급은 후순위다. 기존 구현과 호환을 맞추지 않는다([ADR 0337-2](../../../../adr/0337-2-payment-replacement.md)).

- **이 계획이 맡는 것**: Payment 전체(Phase 1·3·후순위)와 결제에 연관된 주문 흐름(Phase 2 주문 연동). 결제와 주문이 겹치는 설계는 이 계획이 우선한다.
- **도메인 분리**: Phase 2는 #337의 하위 이슈로 두되 `service:commercecore` 라벨로 진행한다. 두 도메인은 계약(`contracts/`)과 ADR로만 잇는다.
- **CommerceCore 범위**: 재고·쿠폰, 주문 모듈 구조(#331).
- **서비스 소유**: 서비스마다 독립 팀이 맡는다고 가정한다. 이슈 하나는 소유 서비스 하나의 구현만 요구하고, 서비스 사이의 약속은 `contracts/`와 ADR로 잇는다.
- **범위 밖**(만들지 않음, 필요하면 별도 요구사항): 토스 호출 동시 실행 수 제한(벌크헤드)·웹훅, 토스 지급대행 실연동·셀러 등록(지급은 Mock), 스케줄러 분산 락, Redis 등 경합 최적화.

## 로드맵

**Phase**는 이 계획을 나누는 작업 단위다(마일스톤·단계라고 부르지 않는다). Phase 1의 종료 기준은 [Phase 1 구현 기록](phase1-payment.md#종료-검증)에, Phase 2·3의 종료 기준은 이 문서에 있다. 스펙 본문은 요구사항 문서에만 쓴다. `Phase n`·`PR<n>` 번호는 이 계획 안에서만 쓰는 이름이다. 요구사항·ADR·AGENTS·이슈·워크로그에는 쓰지 않고 이슈 번호나 내용으로 가리킨다.

| Phase | 목표 | 이슈 | 요구사항 | 상태 |
|---|---|---|---|---|
| 0 스펙 | 스펙·결정·계획 문서 | #337 (PR #339) | 이 폴더, 요구사항 3개, ADR 0337-1–5 | 완료 |
| **1 결제 1차** | 기존 코드 삭제 | #347 | — | 완료 (PR #352) |
| | 결제 생성·결제창·조회 API | #348 | PAY-DATA·CREATE·CHECKOUT·QUERY·AUTH | 완료 (PR #354·#355) |
| | 토스 승인과 원장 분개 | #338 | PAY-CONFIRM·PG·EVENT, LED-01–05·APPROVE-01 | 완료 (PR #353·#356–#358, 토스 샌드박스 확인 #366) |
| | 결과 불명 복구와 만료 | #349 | PAY-RECOVER·EXPIRE | 완료 (PR #359) |
| **2 주문 연동** | Commerce가 새 결제 API·이벤트를 쓴다 | 완료 #343·#365·#383·#377·#389·#390·#399·#345, 확인 남음 #367 | CommerceCore 문서([주문·결제 흐름](../../../../design-docs/flows/order-payment.md)) | 코드 머지 완료, #367 확인 남음 |
| **3 환불** | Core가 Kafka로 보낸 환불·보상 요청을 실행하고 결과를 보낸다 | 완료 #385·#370·#386·#384·#398·#402·#369, 확인 남음 #401·#387 | REF-*, LED-REFUND-01 | 코드 머지 완료, #401·#387 확인 남음 |
| 다음 범위 | 토스 장애 때 새 결제를 Mock 2차 PG로 보낸다 | #400 | 착수 때 요구사항 갱신 | 대기 |
| 후순위 | 대사 → 정산 → 지급 → 선택(웹훅) | 착수 때 | 착수 때 `requirements/reconciliation.md` 등 | 대기 |

### Phase 2·3의 남은 일

| 소유 | 이슈 | 끝나면 달라지는 것 | 선행 | 상태 |
|---|---|---|---|---|
| 공유 계약 | #384 | 환불 요청·결과 계약과 ADR이 현재 구현과 맞는다 | #377 | 닫힘(PR #393·#408) |
| Payment | #398 | 승인 429 뒤 이중 청구와 미환불이 생기지 않는다 | 없음 | 닫힘(PR #405) |
| Payment | #401 | 토스의 환불 진행 중 응답이 실패로 끝나지 않는다 | 없음 | 열림. PR #406 머지, 토스 실제 응답 미재현 |
| Payment | #402 | 환불 소비자가 확정 계약과 공통 오류 처리기를 쓴다 | #384, #389 | 닫힘(PR #411) |
| Payment | #387 | 오래 미확정인 결제·환불 건수를 지표로 낸다 | 없음 | 열림. PR #413 머지, Infra #64 알림 확인 남음 |
| Payment | #369 | 24시간 상한에 걸린 환불을 사람이 재개할 수 있다 | 없음 | 닫힘(PR #414) |
| CommerceCore | #377 | 보상 대기 주문이 있어도 새 구매를 할 수 있다 | 없음 | 닫힘(PR #378·#407) |
| CommerceCore | #389 | 처리 못 할 메시지가 DLT로 가고 뒤 메시지가 막히지 않는다 | #390 | 닫힘(PR #410) |
| CommerceCore | #390 | 옛 Payment 호출과 하이픈 토픽 구독이 사라진다 | #377 | 닫힘(PR #409) |
| CommerceCore | #399 | 앞선 환불이 미확정이어도 남은 상품을 환불할 수 있다 | #377, #390, #401 | 닫힘(PR #412) |
| CommerceCore | #367 | 구매자 일반 환불의 Core 완료 조건을 확인한다 | 없음 | 열림. 완료 조건 확인 남음 |
| CommerceCore | #345 | 일부 수량 환불의 남은 조건을 확인한다 | #399 | 닫힘(PR #412) |
| Infra | [Infra #64](https://github.com/2026-2-rising-sun/Infra/issues/64) | DLT 토픽·30일 보관·Slack 알림이 동작한다 | 미확정 알림만 #387 | 열림 |
| Frontend | [Frontend #89](https://github.com/2026-2-rising-sun/Frontend/issues/89) | 구매자가 결제·환불 상태를 구분하고 남은 상품을 환불한다 | Backend·Infra 완료 | 열림 |

작업 순서(1–5는 dev 머지 완료):

1. 선행이 없는 Payment, CommerceCore, Infra DLT 작업을 서비스별로 병렬 진행한다.
2. CommerceCore는 #377 → #390 → #389 순서로 진행한다. 같은 결제 코드와 소비자 설정을 고쳐 병렬로 하면 충돌한다.
3. #377 뒤에 #384로 계약을 확정하고, #384와 #389 뒤에 #402를 진행한다.
4. #377, #390, #401 뒤에 #399를 진행한다.
5. #387 지표로 Infra #64의 미확정 알림을 넣는다.
6. #345를 확인해 닫고, Backend·Infra가 끝나면 Frontend #89를 연동한다.

이번 범위의 종료 기준:

- 위 표의 이슈가 모두 닫힌다.
- dev와 Infra main으로 주문 → 승인 → 부분·전체 환불 E2E가 통과한다.
- 승인 429, 미확정 환불 중 다른 상품 환불, DLT 유입 알림을 실제 실행으로 확인한다.
- 흐름 문서, ADR, 계약, 이 문서가 현재 구현과 맞는다. 끝나면 계획을 `completed/`로 옮긴다.

모든 Phase의 이슈는 #337의 GitHub 하위 이슈로 연결하고, #337 본문의 로드맵 체크리스트에 추가한다. 이슈 하나 = 확인 가능한 기능 하나(PR 1–4개). PR은 자기 이슈를 `Refs`로 연결하고 그 이슈의 마지막 PR에서 `Closes`한다.

### 문서를 나누는 기준

- **이 README**는 모든 Phase의 목표·범위·서비스 소유·순서·의존과 종료 기준을 맡는다. #337의 체크리스트는 진행 상태만 보여 주고 이 문서를 링크한다.
- **Phase 1**은 처음 전체를 새로 만드는 작업이라 [별도 구현 기록](phase1-payment.md)이 있다. 이 문서는 기록으로 유지하고 끝나면 계획과 함께 `completed/`로 옮긴다.
- **Phase 2·3**은 별도 Phase 문서를 만들지 않는다. 동작은 요구사항과 이슈, 서비스 간 약속은 `contracts/`, 결정은 ADR, 흐름은 `design-docs/flows/`에 둔다.
- **새 Phase 문서**는 여러 이슈에 걸친 새 설계가 필요할 때만 착수 전에 쓴다. #400과 정산이 후보다.
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

1. **스펙 먼저**: 동작이나 계약이 바뀌면 요구사항, 계약, ADR을 구현과 같은 PR 또는 앞선 PR에서 고친다.
2. **Red**: 이슈 완료 조건과 요구사항 ID마다 실패하는 테스트를 쓴다. `@DisplayName`에 ID를 넣는다. 테스트만 먼저 커밋한다.
3. **Green**: 구현한다. 이때 테스트를 고치지 않는다. 테스트가 틀렸으면 멈추고 PR 본문에 이유를 적은 뒤 고친다.
4. **Refactor → 검증 → 독립 리뷰**: 아래 PR 게이트. 푸시는 Green 뒤에만 한다(자식 PR이 빨간 CI를 물려받지 않게).
5. **기록**: 요구사항 체크박스, 이슈 완료 조건, [워크로그](../../../2026-11-13.md) 항목. 스펙이 바뀌면 요구사항 문서를 먼저 고친다.
   - 워크로그는 PR 하나에 항목 하나다(세션마다 쓰지 않는다). [팀 템플릿](../../../../templates/worklog.md)의 제목 `## #<이슈> <PR 제목>`과 4항목(변경·판단 · 검증 · 미실행·한계 · 다음 작업)을 한 줄씩 쓰고 PR 링크를 단다. 자세한 내용은 PR 본문과 CI가 가진다.
   - Phase 상태는 이 계획, 이슈 상태는 GitHub가 가진다. 워크로그는 다시 읽지 않는 근거 기록이고, 요구사항 `근거:`에서 항목 제목 앵커로만 연결한다.

사소한 변경(이름, 설정 한 줄)은 1·2를 건너뛴다.

### PR 게이트

1. 이슈 완료 조건과 요구사항 ID마다 테스트가 있다. PR 본문 "검증"에 `ID → 테스트`를 적는다.
2. `scripts/check.sh --base <부모 브랜치 또는 origin/dev>` 통과. Payment postgres 테스트는 `PAYMENT_TEST_POSTGRES_URL/USER/PASSWORD`와 `--external`로 실행한다([도구](../../../../guides/tooling.md)).
3. 이슈가 지정한 문서를 코드와 함께 고쳤다. 요구사항·API·상태를 바꾸면 [Payment 목표 설계](../../../../design-docs/services/payment/design.html)도 같은 PR에서 고친다. `scripts/check-docs.sh`, `scripts/render-api-docs.sh --check` 통과.
4. CI 통과. size 라벨 `size/L` 이하. 넘으면 PR 본문에 이유 한 줄(`db/payment-migration` SQL과 삭제 줄도 크기에 들어간다).
5. 작성자가 아닌 리뷰어의 독립 리뷰. 질문: ID마다 테스트가 실제로 그 조건을 검사하나 / 돈이 두 번 움직이지 않나 / 모든 상태 변경이 이전 상태 조건부 UPDATE이고 0행을 처리하나 / 트랜잭션 안에서 토스를 부르지 않나 / 범위 밖을 구현하지 않았나.

### 스택 PR

- PR base는 바로 앞 PR 브랜치, 첫 PR은 `dev`. 브랜치는 `<type>/#<그 PR의 하위 이슈>-<slug>`다.
- 스택은 아래 PR부터 **merge commit**("Create a merge commit")으로 병합한다. squash·rebase 병합은 쓰지 않는다. 자식 브랜치가 부모 커밋을 그대로 가지고 있어 rebase·강제 푸시가 필요 없다.
- 저장소는 병합 뒤 브랜치를 지우지 않아(`delete_branch_on_merge: false`) 자식 PR의 base가 자동으로 바뀌지 않는다. 부모가 병합되면 자식 base를 `dev`로 바꾼 뒤 병합한다.

```bash
gh pr edit <내-PR> --base dev && gh pr ready <내-PR>
```


## 이후 범위

| 범위 | PR (예상) |
|---|---|
| 서킷 브레이커·Mock 2차 PG | #400. Payment가 PG를 고른다. 결제창 연동에 Core·Frontend 변경이 필요하면 착수 때 서비스별 이슈로 나눈다 |
| 후순위 | 대사: V6 + 원장 대사 → 거래 대사·자동 보정(EC 38). 정산: V7 + `PurchaseConfirmed` → NORMAL → REVERSAL. 지급: V8 + Mock 지급. 선택: 웹훅 |

## Commerce 의존

| 필요한 것 | 소유 | 상태 |
|---|---|---|
| 새 생성·승인 API 호출, `PaymentApproved` 소비 | CommerceCore | dev 병합 |
| `commerce.refund.requests.v1` 발행, `payment.refund.results.v1` 소비 | CommerceCore | dev 병합. 계약 확정 #384 완료 |
| `PurchaseConfirmed` 발행 | CommerceCore | 정산 착수 때 |

Payment 쪽 검증은 Commerce 없이 끝나게 만든다.

## 인계

- 완료: 목표 설계, 요구사항, ADR, Phase 1 구현과 토스 샌드박스 확인, Core 결제 중계, Payment 환불 실행(#385·#370·#386), Phase 2·3의 Backend 코드 작업(위 표의 닫힌 이슈).
- 남은 일: 위 [Phase 2·3의 남은 일](#phase-23의-남은-일)의 열린 이슈와 [다음 Phase 후보](#처음-읽는-사람을-위한-요약).
- 결제 재설계가 끝나면 `design-docs/services/payment/design.html` 상단의 "구현 전 목표" 안내를 지우고 모듈 문서의 현재 코드 설명과 맞춘다.
