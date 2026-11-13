# Notification 방송·재입고 구독

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](subscription.html)

## 범위와 불변조건

신청 상태는 구독 테이블의 행 존재다. 등록 시 Live/Core를 동기 호출하지 않고 수신한 사본을 사용한다. 이 표는 등록·해제 API의 수명 주기이며 이벤트 발송 결과·사본 상태는 별도 소비 흐름에서 확인한다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 미신청 | 방송 신청 | 받아 둔 broadcast_schedule 사본이 있어야 한다. 없으면 404다. |
| 미신청 | 재입고 신청 | 판매 사본이 없으면 404, 실제 raw_status가 SOLD_OUT이 아니면 409다. |
| 방송 신청 | 방송 신청 | 동일 회원·방송의 재신청은 유일키 기반 멱등 처리다. |
| 재입고 신청 | 재입고 신청 | 동일 회원·상품의 재신청은 유일키 기반 멱등 처리다. |
| 방송 신청 | 미신청 | 본인 구독 해제. 이미 없는 신청도 성공한다. |
| 재입고 신청 | 미신청 | 본인 구독 해제. 이미 없는 신청도 성공한다. |

## 구현과 검증 근거

- [BroadcastAlertService.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/broadcast/service/BroadcastAlertService.java)
- [RestockAlertService.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/sales/service/RestockAlertService.java)
- [BroadcastAlertRepository.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/broadcast/repository/BroadcastAlertRepository.java)
- [RestockAlertRepository.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/sales/repository/RestockAlertRepository.java)

관련 기존 테스트: [BroadcastAlertHttpTest.java](../../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/broadcast/controller/BroadcastAlertHttpTest.java) · [RestockAlertHttpTest.java](../../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/sales/controller/RestockAlertHttpTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
