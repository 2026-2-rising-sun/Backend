# Live 방송 상태

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](broadcast.html)

## 범위와 불변조건

PREPARING에서 종료하거나 ENDED를 다시 시작하면 충돌이다. 준비 정보 수정은 PREPARING과 expectedVersion 일치가 필요하다. 방송 종료 자체가 주문·결제·재고·외부 송출을 종료하지 않는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PREPARING | 등록 원문과 멱등 키를 검증해 준비 상태로 저장한다. |
| PREPARING | LIVE | 외부 준비 확인 이후 버전·동일 채널 LIVE 유일성 조건을 검사하고 시작 시각을 기록한다. |
| LIVE | LIVE | 시작 재요청은 기존 시작 시각을 보존한다. 멱등 판정이 버전 검사보다 먼저다. |
| LIVE | ENDED | 종료 시각을 기록하고 관련 종료 이벤트·연결 처리를 수행한다. |
| ENDED | ENDED | 반복 종료는 최초 종료 시각을 보존한다. |

## 구현과 검증 근거

- [Broadcast.java](../../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/Broadcast.java)
- [BroadcastService.java](../../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/service/BroadcastService.java)

관련 기존 테스트: [BroadcastStartTest.java](../../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/broadcast/service/BroadcastStartTest.java) · [BroadcastEndTest.java](../../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/broadcast/service/BroadcastEndTest.java) · [BroadcastLifecycleEventTest.java](../../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/broadcast/service/BroadcastLifecycleEventTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
