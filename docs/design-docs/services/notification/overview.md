# Notification

책임: 이벤트 기반 알림함·방송/재입고 구독·방송/판매/주문 조회 상태.

알림과 조회 사본은 거래 원본이 아니다. 회원 소유권·중복 이벤트·버전 조건을 소비 코드에서 확인한다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/notification-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [알림 읽음·보존](overview.md) · [시각 자료](overview.html)
- [방송·재입고 구독](overview.md) · [시각 자료](overview.html)

## 원본·갱신

[구현](../../../../services/notification-service/src/main/java) · [마이그레이션](../../../../services/notification-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.

## Notification 알림 읽음·보존

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

상태 enum 대신 read_at과 행 존재로 표현한다. 타인 소유와 존재하지 않는 ID는 모두 404다. 알림 삭제는 원본 주문·결제·환불 기록을 지우지 않는다. 서비스 주석의 과거 이벤트 미구현 표현보다 실제 이벤트 소비·저장 코드를 함께 읽는다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 이벤트 | 안 읽음 | 이벤트 ID와 회원 ID의 유일 조건으로 알림을 저장한다. 중복 이벤트가 같은 알림을 늘리지 않게 한다. |
| 안 읽음 | 읽음 | 본인 알림만 read_at IS NULL 조건으로 갱신한다. 목록 조회만으로 읽음 처리하지 않는다. |
| 읽음 | 읽음 | 재요청과 전체 읽음 처리에서 최초 read_at을 보존한다. |
| 안 읽음 | 보존 종료 | created_at이 보관 기준보다 오래되면 읽음 여부와 별도로 제한된 수량을 삭제한다. |
| 읽음 | 보존 종료 | 기본 P30D 보관 기준·회당 최대 10000개를 사용한다. 삭제 실패는 로그 후 다음 주기에 재시도한다. |

### 구현과 검증 근거

- [NotificationInboxService.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/service/NotificationInboxService.java)
- [NotificationWriteRepository.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/repository/NotificationWriteRepository.java)
- [NotificationRetentionScheduler.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/service/NotificationRetentionScheduler.java)

관련 기존 테스트: [NotificationInboxHttpTest.java](../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/inbox/controller/NotificationInboxHttpTest.java) · [NotificationRetentionTest.java](../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/inbox/repository/NotificationRetentionTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.

## Notification 방송·재입고 구독

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](overview.html)

### 범위와 불변조건

신청 상태는 구독 테이블의 행 존재다. 등록 시 Live/Core를 동기 호출하지 않고 수신한 사본을 사용한다. 이 표는 등록·해제 API의 수명 주기이며 이벤트 발송 결과·사본 상태는 별도 소비 흐름에서 확인한다.

### 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 미신청 | 방송 신청 | 받아 둔 broadcast_schedule 사본이 있어야 한다. 없으면 404다. |
| 미신청 | 재입고 신청 | 판매 사본이 없으면 404, 실제 raw_status가 SOLD_OUT이 아니면 409다. |
| 방송 신청 | 방송 신청 | 동일 회원·방송의 재신청은 유일키 기반 멱등 처리다. |
| 재입고 신청 | 재입고 신청 | 동일 회원·상품의 재신청은 유일키 기반 멱등 처리다. |
| 방송 신청 | 미신청 | 본인 구독 해제. 이미 없는 신청도 성공한다. |
| 재입고 신청 | 미신청 | 본인 구독 해제. 이미 없는 신청도 성공한다. |

### 구현과 검증 근거

- [BroadcastAlertService.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/broadcast/service/BroadcastAlertService.java)
- [RestockAlertService.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/sales/service/RestockAlertService.java)
- [BroadcastAlertRepository.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/broadcast/repository/BroadcastAlertRepository.java)
- [RestockAlertRepository.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/sales/repository/RestockAlertRepository.java)

관련 기존 테스트: [BroadcastAlertHttpTest.java](../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/broadcast/controller/BroadcastAlertHttpTest.java) · [RestockAlertHttpTest.java](../../../../services/notification-service/src/integrationTest/java/com/shoppinglive/notification/sales/controller/RestockAlertHttpTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
