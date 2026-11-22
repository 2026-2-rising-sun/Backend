# 흐름과 서비스 설계

## 서비스 간 흐름

| 주제 | 문서 |
|---|---|
| 주문 준비·결제 승인·만료·늦은 승인 | [주문·결제](flows/order-payment.md) |
| 환불 접수·실행·결과 반영 | [환불](flows/refund.md) |
| 상품·판매·쿠폰 조회 사본 | [Catalog 동기화](flows/catalog-sync.md) |
| JWT·서버 세션 확인 | [인증](flows/authentication.md) |
| 방송·채팅·알림 전달 | [방송·알림](flows/live-notification.md) |
| 로컬 미들웨어·서비스별 DB·검증 환경 | [로컬 실행 환경](flows/local-environment.md) |

## 서비스별 설계

| 서비스 | 책임과 문서 |
|---|---|
| [CommerceCore](services/commercecore/overview.md) | Product·SKU·Cart·Order·Coupon 원본과 모듈별 ERD·API |
| [Payment](services/payment/overview.md) | 결제·환불 실행. 결제 1차 현재 코드와 재설계 목표 구분 |
| [Catalog](services/catalog/overview.md) | 공개 조회 사본 |
| [Member](services/member/overview.md) | 회원·인증·세션 |
| [Live](services/live/overview.md) | 방송·채팅·실시간 전달 |
| [Notification](services/notification/overview.md) | 알림·구독·조회 사본 |
| [common-module](services/common-module/overview.md) | 인증·응답·감사·Outbox 공통 구현 |

[제품 개요](overview.md) · [공통 용어](glossary.md) · [전체 서비스 경계](../../ARCHITECTURE.md) · [문서 관리 기준](../README.md)

흐름은 서비스 간 연결, 모듈 문서는 해당 모듈의 데이터·업무 규칙을 설명한다. MD·HTML은 한 쌍으로 관리하며 결정 이유는 ADR, 실제 검증은 워크로그로 연결한다.
