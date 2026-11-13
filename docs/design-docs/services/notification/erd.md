# Notification DB ERD

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| notification | PK id; UQ (event_id,member_id); event_id VARCHAR(64); read_at; link_type/link_value | [V1](../../../../services/notification-service/src/main/resources/db/migration/V1__notification_baseline.sql) · [V2](../../../../services/notification-service/src/main/resources/db/migration/V2__notification_event_keys.sql) · [V11](../../../../services/notification-service/src/main/resources/db/migration/V11__notification_event_id_length.sql) |
| broadcast_schedule | PK broadcast_id; broadcast_version; scheduled_at; started_at; starting_soon_sent_at | [V3](../../../../services/notification-service/src/main/resources/db/migration/V3__broadcast_schedule.sql) · [V5](../../../../services/notification-service/src/main/resources/db/migration/V5__broadcast_starting_soon.sql) · [V9](../../../../services/notification-service/src/main/resources/db/migration/V9__broadcast_started_at.sql) |
| broadcast_alert | PK id; UQ (member_id,broadcast_id) | [V4](../../../../services/notification-service/src/main/resources/db/migration/V4__broadcast_alert.sql) |
| product_sales_status | PK product_id; sales_id; snapshot_version; in_stock; raw_status | [V6](../../../../services/notification-service/src/main/resources/db/migration/V6__product_sales_status.sql) |
| restock_alert | PK id; UQ (member_id,product_id) | [V7](../../../../services/notification-service/src/main/resources/db/migration/V7__restock_alert.sql) |
| payment_group | PK group_number; member_id; expires_at; closed_at; expiring_soon_sent_at | [V10](../../../../services/notification-service/src/main/resources/db/migration/V10__payment_group.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| notification.member_id | Member.members.id | 서비스 간 ID · FK 없음 |
| broadcast_alert.broadcast_id | broadcast_schedule.broadcast_id | 논리 참조 · 일정 사본 확인, FK 없음 |
| restock_alert.product_id | product_sales_status.product_id | 논리 참조 · 판매 사본 확인, FK 없음 |
| broadcast_schedule.broadcast_id | Live.broadcast.id | 서비스 간 원본 ID · 버전 사본 |
| payment_group.group_number | Core.payment_group.group_number | 서비스 간 원본 번호 · FK 없음 |

## 제약과 이관

이 마이그레이션 집합에는 물리 FK가 없다. event_id+member_id로 중복을 막으며 V11에서 event_id를 64자로 확장했다. 구독은 행 존재로, 읽음은 read_at으로 표현한다.

## 원본과 매핑

- [V1__notification_baseline.sql](../../../../services/notification-service/src/main/resources/db/migration/V1__notification_baseline.sql)
- [V2__notification_event_keys.sql](../../../../services/notification-service/src/main/resources/db/migration/V2__notification_event_keys.sql)
- [V3__broadcast_schedule.sql](../../../../services/notification-service/src/main/resources/db/migration/V3__broadcast_schedule.sql)
- [V4__broadcast_alert.sql](../../../../services/notification-service/src/main/resources/db/migration/V4__broadcast_alert.sql)
- [V5__broadcast_starting_soon.sql](../../../../services/notification-service/src/main/resources/db/migration/V5__broadcast_starting_soon.sql)
- [V6__product_sales_status.sql](../../../../services/notification-service/src/main/resources/db/migration/V6__product_sales_status.sql)
- [V7__restock_alert.sql](../../../../services/notification-service/src/main/resources/db/migration/V7__restock_alert.sql)
- [V9__broadcast_started_at.sql](../../../../services/notification-service/src/main/resources/db/migration/V9__broadcast_started_at.sql)
- [V10__payment_group.sql](../../../../services/notification-service/src/main/resources/db/migration/V10__payment_group.sql)
- [V11__notification_event_id_length.sql](../../../../services/notification-service/src/main/resources/db/migration/V11__notification_event_id_length.sql)

JPA 매핑: [Notification.java](../../../../services/notification-service/src/main/java/com/shoppinglive/notification/inbox/entity/Notification.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
