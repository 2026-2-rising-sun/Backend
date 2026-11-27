# Backend 아키텍처

기준: 2026-11-20 dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`의 코드·계약·설정. 결제·환불 부분만 2026-11-27 dev `ba9be27c`(#416~#418 머지 뒤)로 고쳤다. [CDC/Catalog #379](https://github.com/2026-2-rising-sun/Backend/pull/379)와 [Core 환불 #378](https://github.com/2026-2-rising-sun/Backend/pull/378)은 병합됐다. 로컬 실행 근거와 운영·외부 서비스 미확인 범위는 [검증 기록](docs/worklog/2026-11-20-cdc-refund-status.md)에서 구분한다.

[프로젝트 소개](README.md) · [모듈 문서](docs/design-docs/README.md) · [업무 흐름](docs/design-docs/README.md)

## 모듈과 데이터 소유권

| 모듈 | 소유 책임 | 경계 |
|---|---|---|
| Member | 회원·자격증명·접근/갱신 세션 | 다른 서비스는 Member의 세션 확인 계약을 사용한다. |
| CommerceCore | 상품·이미지·판매·재고·쿠폰·장바구니·주문·결제/환불 접수와 결과 반영 | 거래 정책과 재고·쿠폰 보상은 Core 책임이다. |
| Payment | 결제 생성·토스 승인·UNKNOWN 복구·만료·원장 분개·결과 Outbox(환불은 재설계 중) | Core의 영속 전달 ID를 사용하되 독립 DB를 소유한다. |
| Catalog | 공개 상품·판매·쿠폰 조회 사본 | 원본 변경 주체는 Core다. 이벤트 사본과 HTTP 대조/대체 경로를 구분한다. |
| Live | 방송·상품 연결·프로모션·채팅·좋아요·시청 연결 | Redis Pub/Sub은 Live 인스턴스 간 SSE 전달에 사용한다. |
| Notification | 알림·구독·이벤트 중복 방지·방송/판매 조회 상태 | 소비한 이벤트로 본인 알림함을 구성한다. |
| common-module | 응답·인증·추적·감사·공통 Outbox 구현 | 독립 서비스/DB가 아니다. 업무 정책을 편의상 공통화하지 않는다. |

모듈 등록의 원본은 [settings.gradle.kts](settings.gradle.kts), 계약은 [OpenAPI](contracts/api)와 [이벤트](contracts/events)다. 서비스 사이 DB JOIN·FK와 Java 구현 참조를 만들지 않는다. 공통 모듈에서 서비스 구현으로 역의존하지 않는다.

## 레이어와 통신 방향

서비스 내부는 업무 도메인 아래 Controller → Service → Repository/Entity, 외부 호출은 대상별 Client로 구성한다. 트랜잭션·멱등성·잠금·복구 경계는 실제 서비스 코드에서 확인한다.

- Core → Payment: 새 주문서의 결제 생성·승인·조회는 영속 원문을 둔 HTTP 중계다. 기존 legacy 환불 HTTP와 늦은 승인 환불 요청 Outbox도 남아 있지만 실제 환불 실행 연동은 미완이다. Payment → Core: 결제 결과 이벤트·대조 조회, Core에 구현된 환불 결과 소비를 구분한다.
- Core → Catalog: 상품·SKU 메타데이터는 업무 변경과 같은 트랜잭션의 Outbox에 기록하고, 변경된 재고는 기본 60초 fixedDelay 배치로 합친다. Connect가 Catalog 토픽의 삽입·초기 snapshot만 선별해 Kafka로 보낸다. Core relay는 이 토픽을 제외하고 기존 판매·쿠폰·주문 알림 경로는 유지한다.
- Catalog는 상품·SKU 메타데이터와 재고의 버전을 독립 적용한다. 사본 재고는 지연된 표시 정보이며 주문 시 실제 가격·재고는 Core가 최종 검증한다. 기존 HTTP 판매/쿠폰 조회 설정과 새 CDC 초기 적재를 구분한다.
- Live → Core/Member: 상품·판매·회원 표시 정보 확인. 방송·프로모션 이벤트는 관련 소비자에 전달한다.
- Catalog/Core/Live/Notification → Member: 접근 세션 활성 상태 확인.
- Core/Live → Notification: 주문·쿠폰·판매·방송 이벤트. 중복·순서 처리는 소비 코드와 계약을 함께 읽는다.

[주문·결제](docs/design-docs/flows/order-payment.md) · [환불](docs/design-docs/flows/refund.md) · [Catalog 동기화](docs/design-docs/flows/catalog-sync.md) · [인증](docs/design-docs/flows/authentication.md) · [방송·알림](docs/design-docs/flows/live-notification.md)

## 외부 환경과 복구

각 서비스의 PostgreSQL 스키마는 Flyway와 엔티티가 설명한다. Payment는 `db/payment-migration/`, 다른 서비스는 `db/migration/`를 사용한다. Outbox는 DB 상태와 발행 요청을 같은 트랜잭션에 저장하지만 브로커 전달·소비 반영까지 한 번에 보장하는 것은 아니다.

결제 PG 어댑터는 토스페이먼츠를 대상으로 한다. Payment는 `commerce.refund.requests.v1`을 소비해 토스 취소를 실행하고 `payment.refund.results.v1`으로 결과를 보낸다(이슈 #385·#370·#386). 실제 PG 환불은 토스 테스트 환경 확인(#367)까지이고 운영은 미확인이다. UNKNOWN은 확정 실패와 구분한다. IVS 로컬 stub과 실제 어댑터, H2·PostgreSQL, EmbeddedKafka·외부 Kafka의 검증을 분리한다. [실행 조건](docs/guides/tooling.md)을 따른다.

## Order 처리 기한 후속

현재 dev는 주문 생성부터 600초(결제 대기), 승인 시도마다 그 접수부터 별도 600초(처리 기한)를 적용한다. 결제 대기 만료는 EXPIRED와 선점 해제다. 처리 기한에 닿은 결제 확인 중 주문은 COMPENSATION_PENDING으로 바꾸고 같은 TX에서 선점을 해제하고 시스템 보상(`APPROVAL_UNKNOWN`)을 공통 dot 토픽으로 요청한다. 보상 없이 닫힌 주문의 늦은 승인은 `core_refund_intent`에 `LATE_APPROVAL` 보상으로 저장한다. 옛 `payment_relay_refund`와 하이픈 토픽 경로는 #390에서 삭제됐다. [주문·결제 흐름](docs/design-docs/flows/order-payment.md)과 [환불 흐름](docs/design-docs/flows/refund.md)을 함께 확인한다.
