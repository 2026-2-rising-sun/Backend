# 모듈 문서 지도

각 서비스 README에서 계약 YAML·API HTML·DB ERD·상태 문서와 근거 코드로 이동한다.

| 모듈 | 책임 |
|---|---|
| [CommerceCore](commercecore/README.md) | 상품·이미지·판매·재고·쿠폰·장바구니·주문·결제/환불 접수와 결과 반영 |
| [Payment](payment/README.md) | 결제·환불 실행과 Mock 제공자 결과·복구·결과 Outbox |
| [Catalog](catalog/README.md) | 상품·판매·쿠폰 공개 조회 사본 |
| [Member](member/README.md) | 회원·로그인·접근 토큰·갱신 토큰 묶음·세션 확인 |
| [Live](live/README.md) | 방송·방송 상품·프로모션·채팅·좋아요·실시간 전달 |
| [Notification](notification/README.md) | 이벤트 기반 알림함·방송/재입고 구독·방송/판매/주문 조회 상태 |


[공통 모듈](common-module/README.md) · [전체 경계](../../../ARCHITECTURE.md)

API는 YAML에서, 설계 HTML은 같은 주제의 MD에서 생성한다. 소스 조사·로컬 화면·실제 외부 환경 검증은 구분한다.
