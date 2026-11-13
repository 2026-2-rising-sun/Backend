# Live 프로모션 상태

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](promotion.html)

## 범위와 불변조건

CLOSED를 다시 열 수 없다. READY에서 바로 닫힌 항목은 wasOpen=false여서 실제 열렸던 프로모션의 종료 이벤트와 구분한다. 자동 만료·방송 종료 경로는 서비스 테스트를 함께 확인한다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 신규 | READY | 방송에 속한 프로모션을 등록한다. |
| READY | OPEN | 방송 행 잠금 안에서 LIVE 상태를 확인한 뒤 opened_at과 선택적인 closes_at을 기록한다. |
| OPEN | OPEN | 재열기는 최초 시각을 유지하는 멱등 처리다. |
| READY | CLOSED | 판매자 close는 CANCELED로 교정한다. 방송 종료 등은 전달된 사유를 유지한다. |
| OPEN | CLOSED | 판매자 종료·만료·방송 종료 사유와 시각을 저장한다. |
| CLOSED | CLOSED | 반복 종료는 기존 상태를 유지한다. |

## 구현과 검증 근거

- [PromotionService.java](../../../../../services/live-service/src/main/java/com/shoppinglive/live/promotion/service/PromotionService.java)
- [BroadcastPromotion.java](../../../../../services/live-service/src/main/java/com/shoppinglive/live/promotion/entity/BroadcastPromotion.java)

관련 기존 테스트: [PromotionLifecycleTest.java](../../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/promotion/service/PromotionLifecycleTest.java) · [PromotionExpiryTest.java](../../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/promotion/service/PromotionExpiryTest.java) · [PromotionBroadcastEndTest.java](../../../../../services/live-service/src/integrationTest/java/com/shoppinglive/live/promotion/service/PromotionBroadcastEndTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
