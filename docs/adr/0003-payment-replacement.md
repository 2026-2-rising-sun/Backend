# ADR 0003: 기존 결제 구현을 호환 없이 교체한다

결정 상태: 제안
구현 상태: [결제 재설계 실행 계획](../exec-plans/active/337-payment-redesign/README.md)의 기존 결제 코드 삭제 단계
관련 자료: [현재 Payment 모듈 문서](../design-docs/services/payment/README.md)

## 문제

현재 Payment는 Mock 실행기 중심(`payment_attempt`, Mock 결과 테이블)이다. 실제 PG 승인, 결과 불명 복구, 원장, 정산을 담을 수 없다. 새 설계와 기존 구현을 함께 두면 같은 이름의 클래스·경로·이벤트가 충돌하고 계약이 둘이 된다.

## 결정과 대안

- 새 설계를 기준으로 Payment 코드·DB·API·이벤트 계약을 교체한다. 기존 동작과 호환을 맞추지 않는다.
- 첫 PR에서 기존 결제 코드를 지운다. 이미 적용된 마이그레이션 V1–V3는 고치지 않고, 새 V4가 기존 결제 테이블을 지운 뒤 새 테이블을 만든다. `event_outbox`(V2·V3)는 공통 outbox가 쓰므로 남긴다.
- API 경로(`/v1/internal/payments`, `/v1/internal/refunds`)와 토픽 이름(`payment.execution-results.v1`, `payment.refund-results.v1`)은 유지하고 내용을 새 계약으로 바꾼다.
- 대안: 새 경로·새 토픽(v2)으로 공존시킨 뒤 기존을 지움. 아키텍처를 새로 설계하므로 공존 비용만 늘어 택하지 않았다.

## 영향

- CommerceCore 결제 연동이 새 계약으로 바뀔 때(주문 연동 단계)까지 통합 개발 환경의 결제가 동작하지 않는다.
- 로컬 Kafka에 옛 형식 메시지가 남아 있으면 토픽을 비운다.
- 현재 설계 문서(`design-docs/services/payment/*`, `order-payment.md`, `refund.md`, `requirements/order-payment.md`)는 교체 PR마다 코드와 함께 바꾼다.
