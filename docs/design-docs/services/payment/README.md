# Payment

책임: 결제·환불 실행, PG(토스) 연동, 원장, 결과 Outbox.

결제 재설계(#337)가 진행 중이다. **새 설계**가 목표이고, **현재 코드** 문서는 재설계 PR에서 코드와 함께 교체한다.

## 새 설계 (결제 재설계)

| 보고 싶은 것 | 문서 |
|---|---|
| 큰 그림·진행 상황 | [실행 계획](../../../exec-plans/active/337-payment-redesign/README.md) · [#337](https://github.com/2026-2-rising-sun/Backend/issues/337) |
| 수용 기준(ID 원본) | [결제](../../../requirements/payment.md) · [원장](../../../requirements/ledger.md) · [환불](../../../requirements/refund.md) |
| 결정 | ADR [0337-1](../../../adr/0337-1-commerce-relay-internal-api.md) · [0337-2](../../../adr/0337-2-payment-replacement.md) · [0337-3](../../../adr/0337-3-payment-result-delivery.md) · [0337-4](../../../adr/0337-4-ledger-double-entry.md) · [0337-5](../../../adr/0337-5-pg-timeout-and-unknown.md) |
| 그림으로 보기 | [목표 설계](design.html) |
| 결제 1차 상세 | [결제 1차 계획](../../../exec-plans/active/337-payment-redesign/phase1-payment.md) · [작은 판단](../../../exec-plans/active/337-payment-redesign/decision.md) |
| 에이전트 주의점 | [Payment 작업 주의점](../../../../services/payment-service/AGENTS.md) |

## 현재 코드 (결제 재설계 구현 중)

결제 재설계의 V4 스키마와 Commerce용 내부 API 3개(결제 생성·결제창 다시 열기·조회)가 있다. 토스 결제 클라이언트(승인·조회·취소, 결과 4분류)와 승인 처리 서비스(`ApprovalService`·`PaymentOutcomeService`)가 있다. 승인 처리는 검증·APPROVING 전환을 커밋한 뒤 트랜잭션 밖에서 토스를 부르고, 결과를 상태·pg_call·승인 분개·`PaymentApproved` outbox에 한 트랜잭션으로 반영한다. 승인 API(`ApprovalController`)는 검증·APPROVING 전환 뒤 토스 호출·반영을 가상 스레드에 넘기고 최대 8초(`payment.approval.response-wait`)만 기다려, 넘으면 202 PENDING으로 답한다. 결과 불명 복구·만료 스케줄러 3개(`ApprovingSweeper`: APPROVING 3분 정체 → UNKNOWN, `UnknownPaymentPoller`: UNKNOWN을 토스에 조회해 확정·마감 30분 뒤 ERROR 로그, `PaymentExpiryJob`: 만료된 CREATED → CANCELED)가 `payment.recovery.enabled`(기본 켜짐)일 때 돈다. 분산 락은 없고 조건부 UPDATE로 한 번만 반영한다(#349). 기존 Mock 결제·환불 코드는 [#347](https://github.com/2026-2-rising-sun/Backend/issues/347)에서 지웠다.

- API: [API HTML](api.html) · [계약 YAML](../../../../contracts/api/payment-service.yaml). `X-Service-Token`(commercecore)만 허용하고, 허용 목록(`/v1/internal/payments`, `…/{orderRef}`, `…/{orderRef}/checkouts`, `…/{orderRef}/approval`) 밖의 `/v1/internal/**`는 403이다.
- 결제 키는 `orderRef`다. 같은 orderRef 생성은 `INSERT … ON CONFLICT DO NOTHING`의 영향 행 수로 판단하고, 결제창은 `CREATED`·만료 전 조건부 UPDATE로 회차를 올린다. 상세는 [#348 설계서](../../../exec-plans/active/337-payment-redesign/phase1/348-create-api.md#api).
- DB: [ERD 설명](erd.md) · [관계도](erd.html)
- 상태: [결제 상태](states/payment.md) · [시각 자료](states/payment.html)
- 결과 이벤트: [계약 YAML](../../../../contracts/events/payment-execution-results.yaml). `PaymentApproved`(키 orderRef)로 교체했다(호환 깨짐). CommerceCore 소비자는 아직 옛 `PaymentExecutionResolved`를 기다리므로 주문 연동(Phase 2) 전까지 통합 환경 결제는 동작하지 않는다.

아래 문서는 지운 Mock 구현의 기록이다. 기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`.

- [환불 실행](states/refund.md) · [시각 자료](states/refund.html)
- 서비스 간 흐름: [주문·결제](../../order-payment.md) · [환불](../../refund.md)

## 원본·갱신

[구현](../../../../services/payment-service/src/main/java) · [마이그레이션](../../../../services/payment-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
