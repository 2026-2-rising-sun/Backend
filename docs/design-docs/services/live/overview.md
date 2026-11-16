# Live

책임: 방송·방송 상품·프로모션·채팅·좋아요·실시간 전달.

상품·판매 원본은 Core, 회원 원본은 Member다. 방송 종료가 주문·재고·송출 종료를 직접 처리하는 것은 아니다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/live-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [방송 상태](overview.md) · [시각 자료](overview.html)
- [프로모션 상태](overview.md) · [시각 자료](overview.html)

## 원본·갱신

[구현](../../../../services/live-service/src/main/java) · [마이그레이션](../../../../services/live-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.

## Live 방송 상태

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

PREPARING에서 종료하거나 ENDED를 다시 시작하면 충돌이다. 준비 정보 수정은 PREPARING과 expectedVersion 일치가 필요하다. 방송 종료 자체가 주문·결제·재고·외부 송출을 종료하지 않는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | PREPARING | 등록 원문과 멱등 키를 검증해 준비 상태로 저장한다. |
| PREPARING | LIVE | 외부 준비 확인 이후 버전·동일 채널 LIVE 유일성 조건을 검사하고 시작 시각을 기록한다. |
| LIVE | LIVE | 시작 재요청은 기존 시작 시각을 보존한다. 멱등 판정이 버전 검사보다 먼저다. |
| LIVE | ENDED | 종료 시각을 기록하고 관련 종료 이벤트·연결 처리를 수행한다. |
| ENDED | ENDED | 반복 종료는 최초 종료 시각을 보존한다. |

### 구현과 검증 근거

- [Broadcast.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/Broadcast.java)
- [BroadcastService.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/service/BroadcastService.java)

관련 기존 테스트: [BroadcastStartTest.java](../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/broadcast/service/BroadcastStartTest.java) · [BroadcastEndTest.java](../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/broadcast/service/BroadcastEndTest.java) · [BroadcastLifecycleEventTest.java](../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/broadcast/service/BroadcastLifecycleEventTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

## Live 프로모션 상태

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

CLOSED를 다시 열 수 없다. READY에서 바로 닫힌 항목은 wasOpen=false여서 실제 열렸던 프로모션의 종료 이벤트와 구분한다. 자동 만료·방송 종료 경로는 서비스 테스트를 함께 확인한다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | READY | 방송에 속한 프로모션을 등록한다. |
| READY | OPEN | 방송 행 잠금 안에서 LIVE 상태를 확인한 뒤 opened_at과 선택적인 closes_at을 기록한다. |
| OPEN | OPEN | 재열기는 최초 시각을 유지하는 멱등 처리다. |
| READY | CLOSED | 판매자 close는 CANCELED로 교정한다. 방송 종료 등은 전달된 사유를 유지한다. |
| OPEN | CLOSED | 판매자 종료·만료·방송 종료 사유와 시각을 저장한다. |
| CLOSED | CLOSED | 반복 종료는 기존 상태를 유지한다. |

### 구현과 검증 근거

- [PromotionService.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/promotion/service/PromotionService.java)
- [BroadcastPromotion.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/promotion/entity/BroadcastPromotion.java)

관련 기존 테스트: [PromotionLifecycleTest.java](../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/promotion/service/PromotionLifecycleTest.java) · [PromotionExpiryTest.java](../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/promotion/service/PromotionExpiryTest.java) · [PromotionBroadcastEndTest.java](../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/promotion/service/PromotionBroadcastEndTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
