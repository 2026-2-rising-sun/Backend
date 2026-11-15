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

## 현재 코드 (Mock 삭제됨)

기존 Mock 결제·환불 코드와 내부 API는 [#347](https://github.com/2026-2-rising-sun/Backend/issues/347)에서 지웠다. 서비스는 컨트롤러 없이 기동하고, 모든 `/v1/internal/**` 요청은 서비스 토큰 검사 뒤 403이다. 새 결제 API는 #348에서 연다. 테이블(V1–V3)은 남아 있고 #348의 V4 마이그레이션에서 지운다. 결과 이벤트 계약 YAML은 CommerceCore 소비자가 쓰고 있어 그대로 둔다.

아래 문서는 지운 Mock 구현의 기록이다. 기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드.

- [API HTML](api.html)(지금은 경로 없음) · [계약 YAML](../../../../contracts/api/payment-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [결제 실행](states/payment.md) · [시각 자료](states/payment.html)
- [환불 실행](states/refund.md) · [시각 자료](states/refund.html)
- 서비스 간 흐름: [주문·결제](../../order-payment.md) · [환불](../../refund.md)

## 원본·갱신

[구현](../../../../services/payment-service/src/main/java) · [마이그레이션](../../../../services/payment-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
