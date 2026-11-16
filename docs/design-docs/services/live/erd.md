# Live DB ERD

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| broadcast | PK id; UQ request_key; channel_arn; status; version; thumbnail_id; host_name; description | [V1](../../../../services/live-service/src/main/resources/db/migration/V1__broadcast.sql) · [V9](../../../../services/live-service/src/main/resources/db/migration/V9__broadcast_metadata.sql) |
| broadcast_product | PK id; UQ (broadcast_id,product_id); sales_id; position | [V2](../../../../services/live-service/src/main/resources/db/migration/V2__broadcast_product.sql) |
| broadcast_chat | PK id; broadcast_id; member_id; display_name; content | [V4](../../../../services/live-service/src/main/resources/db/migration/V4__broadcast_chat.sql) |
| broadcast_like_snapshot | PK/FK broadcast_id; total; updated_at | [V5](../../../../services/live-service/src/main/resources/db/migration/V5__broadcast_like_snapshot.sql) |
| broadcast_like_aggregate | PK/FK broadcast_id; total; version | [V6](../../../../services/live-service/src/main/resources/db/migration/V6__member_likes.sql) |
| broadcast_member_like | PK (broadcast_id,member_id); liked; state_version | [V6](../../../../services/live-service/src/main/resources/db/migration/V6__member_likes.sql) |
| broadcast_like_request | PK (broadcast_id,member_id,request_id); desired; liked; state_version; total; version | [V6](../../../../services/live-service/src/main/resources/db/migration/V6__member_likes.sql) |
| event_outbox | PK id; UQ event_id; payload; published_at; attempts; last_error | [V7](../../../../services/live-service/src/main/resources/db/migration/V7__event_outbox.sql) |
| broadcast_promotion | PK id; UQ request_key; broadcast_id; status; opened_at/closes_at/closed_at; close_reason | [V8](../../../../services/live-service/src/main/resources/db/migration/V8__broadcast_promotion.sql) |
| broadcast_thumbnail | PK id; seller_id; content_type; content | [V9](../../../../services/live-service/src/main/resources/db/migration/V9__broadcast_metadata.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| broadcast_product.broadcast_id | broadcast.id | 물리 FK |
| broadcast_chat.broadcast_id | broadcast.id | 물리 FK |
| broadcast_like_snapshot.broadcast_id | broadcast.id | 물리 FK · 과거 스냅샷 테이블도 마이그레이션에 남음 |
| broadcast_like_aggregate.broadcast_id | broadcast.id | 물리 FK · 현재 좋아요 집계 |
| broadcast_member_like.broadcast_id | broadcast.id | 물리 FK · 회원별 상태 |
| broadcast_like_request.broadcast_id | broadcast.id | 물리 FK · 멱등 요청 결과 |
| broadcast_promotion.broadcast_id | broadcast.id | 물리 FK |
| broadcast.thumbnail_id | broadcast_thumbnail.id | 물리 FK · nullable 대표 이미지 |
| broadcast_product.product_id | Core.product.id | 서비스 간 ID · FK 없음 |
| broadcast_chat.member_id | Member.members.id | 서비스 간 ID · FK 없음 |

## 제약과 이관

동일 channel_arn에서 LIVE는 부분 유일 인덱스로 하나만 허용한다. broadcast_like_snapshot은 삭제 마이그레이션이 없으므로 현재 스키마 목록에 포함했다. Live Outbox에는 Core/Payment의 claim 열을 임의로 추가해 설명하지 않는다.

## 원본과 매핑

- [V1__broadcast.sql](../../../../services/live-service/src/main/resources/db/migration/V1__broadcast.sql)
- [V2__broadcast_product.sql](../../../../services/live-service/src/main/resources/db/migration/V2__broadcast_product.sql)
- [V3__single_live_per_channel.sql](../../../../services/live-service/src/main/resources/db/migration/V3__single_live_per_channel.sql)
- [V4__broadcast_chat.sql](../../../../services/live-service/src/main/resources/db/migration/V4__broadcast_chat.sql)
- [V5__broadcast_like_snapshot.sql](../../../../services/live-service/src/main/resources/db/migration/V5__broadcast_like_snapshot.sql)
- [V6__member_likes.sql](../../../../services/live-service/src/main/resources/db/migration/V6__member_likes.sql)
- [V7__event_outbox.sql](../../../../services/live-service/src/main/resources/db/migration/V7__event_outbox.sql)
- [V8__broadcast_promotion.sql](../../../../services/live-service/src/main/resources/db/migration/V8__broadcast_promotion.sql)
- [V9__broadcast_metadata.sql](../../../../services/live-service/src/main/resources/db/migration/V9__broadcast_metadata.sql)

JPA 매핑: [BroadcastThumbnail.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/thumbnail/BroadcastThumbnail.java) · [BroadcastPromotion.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/promotion/entity/BroadcastPromotion.java) · [MemberLike.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/MemberLike.java) · [LikeAggregate.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/LikeAggregate.java) · [BroadcastChat.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/BroadcastChat.java) · [LikeRequest.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/LikeRequest.java) · [BroadcastProduct.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/BroadcastProduct.java) · [BroadcastLikeSnapshot.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/BroadcastLikeSnapshot.java) · [Broadcast.java](../../../../services/live-service/src/main/java/com/shoppinglive/live/broadcast/entity/Broadcast.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
