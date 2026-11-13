# Backend 아키텍처

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약·설정. #330은 문서·도구만 변경한다. 운영 배포·실제 PG·브로커 실행 결과를 뜻하지 않는다.

[프로젝트 소개](README.md) · [모듈 문서](docs/design-docs/services/README.md) · [업무 흐름](docs/design-docs/README.md)

## 모듈과 데이터 소유권

| 모듈 | 소유 책임 | 경계 |
|---|---|---|
| Member | 회원·자격증명·접근/갱신 세션 | 다른 서비스는 Member의 세션 확인 계약을 사용한다. |
| CommerceCore | 상품·이미지·판매·재고·쿠폰·장바구니·주문·결제/환불 접수와 결과 반영 | 거래 정책과 재고·쿠폰 보상은 Core 책임이다. |
| Payment | 결제·환불 실행·Mock PG·UNKNOWN 복구·결과 Outbox | Core의 영속 전달 ID를 사용하되 독립 DB를 소유한다. |
| Catalog | 공개 상품·판매·쿠폰 조회 사본 | 원본 변경 주체는 Core다. 이벤트 사본과 HTTP 대조/대체 경로를 구분한다. |
| Live | 방송·상품 연결·프로모션·채팅·좋아요·시청 연결 | Redis Pub/Sub은 Live 인스턴스 간 SSE 전달에 사용한다. |
| Notification | 알림·구독·이벤트 중복 방지·방송/판매 조회 상태 | 소비한 이벤트로 본인 알림함을 구성한다. |
| common-module | 응답·인증·추적·감사·공통 Outbox 구현 | 독립 서비스/DB가 아니다. 업무 정책을 편의상 공통화하지 않는다. |

모듈 등록의 원본은 [settings.gradle.kts](settings.gradle.kts), 계약은 [OpenAPI](contracts/api)와 [이벤트](contracts/events)다. 서비스 사이 DB JOIN·FK와 Java 구현 참조를 만들지 않는다. 공통 모듈에서 서비스 구현으로 역의존하지 않는다.

## 레이어와 통신 방향

서비스 내부는 업무 도메인 아래 Controller → Service → Repository/Entity, 외부 호출은 대상별 Client로 구성한다. 트랜잭션·멱등성·잠금·복구 경계는 실제 서비스 코드에서 확인한다.

- Core → Payment: 내부 HTTP로 영속 결제/환불 접수·조회. Payment → Core: 결과 이벤트와 대조 조회로 상태를 반영한다.
- Core → Catalog: 상품·판매·쿠폰 이벤트로 사본을 갱신한다. Catalog → Core: 구매 진입 확인 및 설정에 따른 판매/쿠폰 조회.
- Live → Core/Member: 상품·판매·회원 표시 정보 확인. 방송·프로모션 이벤트는 관련 소비자에 전달한다.
- Catalog/Core/Live/Notification → Member: 접근 세션 활성 상태 확인.
- Core/Live → Notification: 주문·쿠폰·판매·방송 이벤트. 중복·순서 처리는 소비 코드와 계약을 함께 읽는다.

[주문·결제](docs/design-docs/order-payment.md) · [환불](docs/design-docs/refund.md) · [Catalog 동기화](docs/design-docs/catalog-sync.md) · [인증](docs/design-docs/authentication.md) · [방송·알림](docs/design-docs/live-notification.md)

## 외부 환경과 복구

각 서비스의 PostgreSQL 스키마는 Flyway와 엔티티가 설명한다. Payment는 `db/payment-migration/`, 다른 서비스는 `db/migration/`를 사용한다. Outbox는 DB 상태와 발행 요청을 같은 트랜잭션에 저장하지만 브로커 전달·소비 반영까지 한 번에 보장하는 것은 아니다.

결제·환불 제공자는 현재 Mock 구현이다. UNKNOWN은 확정 실패와 구분하고 조회·lease·재시도로 복구한다. IVS 로컬 stub과 실제 어댑터, H2·PostgreSQL, EmbeddedKafka·외부 Kafka의 검증을 분리한다. [실행 조건](docs/guides/tooling.md)을 따른다.
