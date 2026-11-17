# Coupon 데이터 모델

쿠폰 정의·적용 상품·회원 발급 쿠폰을 CommerceCore가 소유한다. 아래 내용은 기존 서비스 ERD의 쿠폰 구간을 모듈별로 분리한 것으로 업무 정책을 추가하지 않는다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| coupon_definition | PK id; seller_id; issuance_limit; issued_count; snapshot_version; starts_at/ends_at/expires_at | [V5](../../../../../services/commercecore-service/src/main/resources/db/migration/V5__coupon_definitions.sql) · [V17](../../../../../services/commercecore-service/src/main/resources/db/migration/V17__coupon_display_snapshot_version.sql) |
| coupon_target | PK (coupon_id,product_id) | [V5](../../../../../services/commercecore-service/src/main/resources/db/migration/V5__coupon_definitions.sql) |
| member_coupon | PK id; UQ (coupon_id,member_id); status AVAILABLE/RESERVED/USED; used_payment_group_id | [V6](../../../../../services/commercecore-service/src/main/resources/db/migration/V6__member_coupons.sql)  · [V104](../../../../../services/commercecore-service/src/main/resources/db/migration/V104__order_sheets_and_closure.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| coupon_target.coupon_id | coupon_definition.id | 물리 FK · 쿠폰 적용 상품 목록 |
| member_coupon.coupon_id | coupon_definition.id | 물리 FK · 회원 발급 쿠폰 |
| payment_group.coupon_id | coupon_definition.id | 물리 FK · 예약한 쿠폰 정의 |

[서비스 전체 관계](../erd.md) · [주문 결과 반영](../order/overview.md) · [API](api.md)
