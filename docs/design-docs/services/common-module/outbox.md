# 공통 Outbox 발행 흐름

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

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

이번 작업은 코드 대조다. ACK·소비 반영·운영 장애 복구를 새로 실행한 증거는 없다.
