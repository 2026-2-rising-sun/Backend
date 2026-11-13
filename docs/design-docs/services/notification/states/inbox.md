# Notification 알림 읽음·보존

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](inbox.html)

## 범위와 불변조건

상태 enum 대신 read_at과 행 존재로 표현한다. 타인 소유와 존재하지 않는 ID는 모두 404다. 알림 삭제는 원본 주문·결제·환불 기록을 지우지 않는다. 서비스 주석의 과거 이벤트 미구현 표현보다 실제 이벤트 소비·저장 코드를 함께 읽는다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 이벤트 | 안 읽음 | 이벤트 ID와 회원 ID의 유일 조건으로 알림을 저장한다. 중복 이벤트가 같은 알림을 늘리지 않게 한다. |
| 안 읽음 | 읽음 | 본인 알림만 read_at IS NULL 조건으로 갱신한다. 목록 조회만으로 읽음 처리하지 않는다. |
| 읽음 | 읽음 | 재요청과 전체 읽음 처리에서 최초 read_at을 보존한다. |
| 안 읽음 | 보존 종료 | created_at이 보관 기준보다 오래되면 읽음 여부와 별도로 제한된 수량을 삭제한다. |
| 읽음 | 보존 종료 | 기본 P30D 보관 기준·회당 최대 10000개를 사용한다. 삭제 실패는 로그 후 다음 주기에 재시도한다. |

## 구현과 검증 근거

- [NotificationInboxService.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/service/NotificationInboxService.java)
- [NotificationWriteRepository.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/repository/NotificationWriteRepository.java)
- [NotificationRetentionScheduler.java](../../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/service/NotificationRetentionScheduler.java)

관련 기존 테스트: [NotificationInboxHttpTest.java](../../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/inbox/controller/NotificationInboxHttpTest.java) · [NotificationRetentionTest.java](../../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/inbox/repository/NotificationRetentionTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
