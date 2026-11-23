# 공통 Outbox 발행 흐름

<!-- visual: relations -->

기준: dev `d42c3b787f12b716789d35c8df37dd63a2b42ddc`에 병합된 #379의 CDC/relay 배타적 전달 변경을 반영한다. 이전 Outbox 동작을 보존하고 Core만 Catalog 토픽을 제외한다. 실제 검사와 미실행 범위는 [최종 검증 기록](../../../worklog/2026-11-20-cdc-refund-status.md)을 따른다. 이전 B 검사 범위는 [당시 기록](../../../worklog/2026-11-19-catalog-cdc-sync.md)에 보존한다.

[시각 자료](outbox.html)

## 책임과 조건

업무 변경과 Outbox 삽입은 소유 서비스의 같은 DB 트랜잭션에 참여한다. 공통 모듈은 독립 DB를 소유하지 않는다. 기본 relay와 선택적인 DB claim 모드를 구분하며 스키마·설정이 준비된 서비스에서만 claim을 사용한다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 업무 트랜잭션 | 미발행 행 | EventOutbox로 메시지 원문과 eventId를 같은 트랜잭션에 저장한다. 롤백되면 함께 취소된다. |
| 미발행 행 | 전송 시도 | 커밋 후 빠른 경로 또는 주기 relay가 선택한다. claim 모드는 token·lease를 확인한다. |
| 전송 시도 | 발행 표시 | Kafka ACK 이후 published_at을 기록한다. claim 모드는 아직 유효한 권한의 결과만 쓴다. |
| 전송 시도 | 재시도 대기 | 실패 횟수·오류를 저장한다. claim 모드는 다음 시각·lease 해제를 반영한다. |
| 재시도 대기 | 전송 시도 | 예약 실행과 조건을 만족한 행을 다시 선택한다. |
| ACK 이후 중단 | 중복 전달 가능 | 브로커 성공 뒤 DB 표시 전에 중단되면 재전송될 수 있다. 소비자는 eventId·버전 조건으로 중복을 처리해야 한다. |

## 구현과 검증 근거

- [EventOutbox.java](../../../../common-module/src/main/java/com/shoppinglive/common/outbox/EventOutbox.java)
- [OutboxRelay.java](../../../../common-module/src/main/java/com/shoppinglive/common/outbox/OutboxRelay.java)
- [OutboxLeaseStore.java](../../../../common-module/src/main/java/com/shoppinglive/common/outbox/OutboxLeaseStore.java)
- [OutboxMessage.java](../../../../common-module/src/main/java/com/shoppinglive/common/outbox/OutboxMessage.java)

[OutboxRelayTest.java](../../../../common-module/src/test/java/com/shoppinglive/common/outbox/OutboxRelayTest.java) · [OutboxTransactionIntegrationTest.java](../../../../common-module/src/integrationTest/java/com/shoppinglive/common/outbox/OutboxTransactionIntegrationTest.java)

실제 실행 범위는 작업별 워크로그를 따른다. 단위/DB 검사 결과를 CDC ACK·소비 반영·운영 장애 복구 증거로 확대하지 않는다.

## CDC와 relay의 배타적 전달

`shoppinglive.outbox.relay.excluded-topics`의 기본값은 빈 목록이라 다른 서비스는 기존 relay 동작을 유지한다. Core만 commerce.catalog.snapshots.v1을 제외한다. 일반 JPA 조회와 DB lease/claim 조회가 모두 같은 제외 목록을 적용하며, fast path도 이 조회를 사용한다. CDC 중단을 감지해 relay로 보내는 fallback은 없다.

Catalog Outbox 행도 기존 event_id/event_type/topic/message_key/payload TEXT/created_at을 그대로 사용한다. Infra #58의 connector는 public.event_outbox의 해당 토픽 create/read만 먼저 필터한 뒤 전체 봉투 TEXT를 원문 value로 보낸다. 관리 UPDATE/DELETE는 비즈니스 이벤트가 아니다. CDC는 published_at/attempts를 relay 완료로 갱신하지 않는다. NULL published_at을 실패나 TTL 정리 가능으로 단정하지 않으며 이번 변경은 CDC 행 삭제를 추가하지 않는다. 보관·slot·offset checkpoint 정리는 별도 운영 설계가 필요하다.

[Catalog 전달·초기 적재·전환 순서](../../flows/catalog-sync.md)를 함께 확인한다. `payment_delivery_request`는 별도의 HTTP 전달 복구 저장소이며 이 CDC가 실행하지 않는다.
