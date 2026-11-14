# CommerceCore DB ERD

<!-- visual: relations -->

기준: Product #333 `729d2d5f`·SKU #334 `1a09aacd`·Cart V103을 반영한다. sales_info/sales_stock은 V102 입력과 기존 Order 이력 참조이며 새 판매 HTTP는 SKU에만 쓴다. Cart는 SKU 조회·선택 포트를 제공하고 기존 Order 거래 전환은 후행 통합 대상이다. [SKU 상세 ERD](../../commercecore/sku/ERD.md)를 함께 본다. 실제 실행 범위는 워크로그와 구분한다.

[시각 자료](erd.html) · [Cart 모듈 상세 ERD](../../commercecore/cart/ERD.md)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| sku | PK id BIGINT; product_id 비유일; price; available/reserved; sale_status; version/snapshot_version; 이름 사본 | [V102](../../../../services/commercecore-service/src/main/resources/db/migration/V102__sku.sql). FK 없는 SKU 원본. 판매 HTTP 전환과 실제 PostgreSQL 검증은 SKU 기록에 있으며 기존 Order 거래 전환은 후행 작업이다. |
| sales_info | PK id; UQ product_id; status; snapshot_version; product_name_snapshot | [V1](../../../../services/commercecore-service/src/main/resources/db/migration/V1__commerce_baseline.sql) · [V16](../../../../services/commercecore-service/src/main/resources/db/migration/V16__sales_snapshot_version.sql) · [V19](../../../../services/commercecore-service/src/main/resources/db/migration/V19__sales_product_name_snapshot.sql) |
| sales_stock | PK/FK sales_info_id; available; reserved | [V1](../../../../services/commercecore-service/src/main/resources/db/migration/V1__commerce_baseline.sql) |
| orders | PK id; UQ order_number, (member_id,idempotency_key); sales_info_id; payment_group_id; status; discount_amount; payable_amount | [V1](../../../../services/commercecore-service/src/main/resources/db/migration/V1__commerce_baseline.sql) · [V2](../../../../services/commercecore-service/src/main/resources/db/migration/V2__member_commerce_expand.sql) · [V3](../../../../services/commercecore-service/src/main/resources/db/migration/V3__member_commerce_contract.sql) · [V4](../../../../services/commercecore-service/src/main/resources/db/migration/V4__payment_groups.sql) · [V7](../../../../services/commercecore-service/src/main/resources/db/migration/V7__order_discount_snapshots.sql) · [V11](../../../../services/commercecore-service/src/main/resources/db/migration/V11__refunded_order_status.sql) |
| cart_item | PK id; product_id 보존; nullable sku_id; UQ (member_id,sku_id); 미매핑 회원+상품 부분 UQ; quantity/version | [V2](../../../../services/commercecore-service/src/main/resources/db/migration/V2__member_commerce_expand.sql) · [V103](../../../../services/commercecore-service/src/main/resources/db/migration/V103__cart_sku_reference.sql). Cart 확장 이행·선택/성공 정리 포트를 실제 PostgreSQL로 검증했으며 전체 Order 연결은 후행 작업이다. |
| member_purchase_guard | PK member_id | [V4](../../../../services/commercecore-service/src/main/resources/db/migration/V4__payment_groups.sql) |
| payment_group | PK id; UQ group_number, (member_id,request_key); coupon_id; payment_id; status; payable_amount | [V4](../../../../services/commercecore-service/src/main/resources/db/migration/V4__payment_groups.sql) · [V7](../../../../services/commercecore-service/src/main/resources/db/migration/V7__order_discount_snapshots.sql) · [V8](../../../../services/commercecore-service/src/main/resources/db/migration/V8__payment_group_coupon_reservation.sql) · [V11](../../../../services/commercecore-service/src/main/resources/db/migration/V11__refunded_order_status.sql) |
| coupon_definition | PK id; seller_id; issuance_limit; issued_count; snapshot_version; starts_at/ends_at/expires_at | [V5](../../../../services/commercecore-service/src/main/resources/db/migration/V5__coupon_definitions.sql) · [V17](../../../../services/commercecore-service/src/main/resources/db/migration/V17__coupon_display_snapshot_version.sql) |
| coupon_target | PK (coupon_id,product_id) | [V5](../../../../services/commercecore-service/src/main/resources/db/migration/V5__coupon_definitions.sql) |
| member_coupon | PK id; UQ (coupon_id,member_id); status AVAILABLE/RESERVED/USED | [V6](../../../../services/commercecore-service/src/main/resources/db/migration/V6__member_coupons.sql) |
| refund_request | PK id; UQ (member_id,idempotency_key); payment_group_id; status; retry_count; next_action_at | [V9](../../../../services/commercecore-service/src/main/resources/db/migration/V9__refund_requests.sql) · [V15](../../../../services/commercecore-service/src/main/resources/db/migration/V15__refund_recovery_leases.sql) |
| refund_target_order | PK (refund_request_id,order_id); product/seller snapshot; refund_amount | [V9](../../../../services/commercecore-service/src/main/resources/db/migration/V9__refund_requests.sql) |
| event_outbox | PK id; UQ event_id; payload; published_at; claim_token/lease_until; next_attempt_at | [V18](../../../../services/commercecore-service/src/main/resources/db/migration/V18__event_outbox.sql) · [V20](../../../../services/commercecore-service/src/main/resources/db/migration/V20__outbox_worker_claim.sql) |
| payment_delivery_request | PK id; UQ request_id, core_refund_request_id; kind; order_id; payment_group_id; payment_id; command; result; status | [V21](../../../../services/commercecore-service/src/main/resources/db/migration/V21__payment_delivery_requests.sql) |
| product_image | PK id; UQ storage_key; content_type; size_bytes; width/height | [V100](../../../../services/commercecore-service/src/main/resources/db/migration/V100__products.sql) |
| product | PK id; UQ idempotency_key; main_image_id; seller_id; category; version; sale_enabled; deleted_at | [V100](../../../../services/commercecore-service/src/main/resources/db/migration/V100__products.sql) · [V101](../../../../services/commercecore-service/src/main/resources/db/migration/V101__product_sale_control.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| sku.product_id | product.id | 논리 참조 · 상품당 여러 SKU, FK 없음. 상품 생성/주문 때 앱 검증. |
| sales_stock.sales_info_id | sales_info.id | 물리 FK · 판매별 재고 1행 |
| orders.sales_info_id | sales_info.id | 물리 FK · 주문의 판매 대상 |
| orders.payment_group_id | payment_group.id | 물리 FK · nullable 묶음 참조 |
| coupon_target.coupon_id | coupon_definition.id | 물리 FK · 쿠폰 적용 상품 목록 |
| member_coupon.coupon_id | coupon_definition.id | 물리 FK · 회원 발급 쿠폰 |
| payment_group.coupon_id | coupon_definition.id | 물리 FK · 예약한 쿠폰 정의 |
| refund_request.payment_group_id | payment_group.id | 물리 FK · 환불 대상 결제 묶음 |
| refund_target_order.refund_request_id | refund_request.id | 물리 FK · 환불 요청별 대상 |
| refund_target_order.order_id | orders.id | 물리 FK · 주문별 환불 결과 |
| payment_delivery_request.order_id | orders.id | 물리 FK · 영속 결제/환불 전달 |
| payment_delivery_request.payment_group_id | payment_group.id | 물리 FK · nullable 묶음 |
| payment_delivery_request.core_refund_request_id | refund_request.id | 물리 FK · 유일 환불 전달 |
| product.main_image_id | product_image.id | N:1 논리 참조 · V101에서 FK 제거, 앱에서 등록/교체 시 존재 확인 |
| sales_info.product_id | product.id | 논리 참조 · UNIQUE이지만 DDL FK 없음 |
| orders.member_id | Member.members.id | 서비스 간 ID · FK 없음 |
| payment_delivery_request.payment_id | Payment.payment_attempt.payment_id | 서비스 간 ID · FK 없음 |

## 제약과 이관

V101은 기존 Product 전체 스위치를 true로 보존하고 신규 기본값만 false로 바꾼다. deleted_at이 있으면 전체 false CHECK를 유지하고 상품·이미지·하위 판매 행을 물리 삭제하지 않는다. [Product 상세 ERD/상태](../../commercecore/product/README.md)에서 통합 목표와 현재 구현을 구분한다.

V3에서 비회원 lookup_password_hash를 제거하고 member_id를 필수화했다. V7 금액은 total_amount와 할인·실결제액을 구분한다. V11은 REFUNDED를 허용한다. V10·V12~V14 등 이관용 파일의 이름만 보고 현재 Core가 PG 테이블을 소유한다고 추정하지 않는다. V21 영속 전달과 Payment 원본을 구분한다. 진행 중 묶음의 회원별 부분 유일 인덱스가 있다.

## 원본과 매핑

- [V1__commerce_baseline.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V1__commerce_baseline.sql)
- [V2__member_commerce_expand.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V2__member_commerce_expand.sql)
- [V3__member_commerce_contract.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V3__member_commerce_contract.sql)
- [V4__payment_groups.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V4__payment_groups.sql)
- [V5__coupon_definitions.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V5__coupon_definitions.sql)
- [V6__member_coupons.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V6__member_coupons.sql)
- [V7__order_discount_snapshots.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V7__order_discount_snapshots.sql)
- [V8__payment_group_coupon_reservation.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V8__payment_group_coupon_reservation.sql)
- [V9__refund_requests.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V9__refund_requests.sql)
- [V10__mock_refund_results.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V10__mock_refund_results.sql)
- [V11__refunded_order_status.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V11__refunded_order_status.sql)
- [V12__payment_unknown_status.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V12__payment_unknown_status.sql)
- [V13__payment_recovery_scenarios.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V13__payment_recovery_scenarios.sql)
- [V14__payment_recovery_leases.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V14__payment_recovery_leases.sql)
- [V15__refund_recovery_leases.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V15__refund_recovery_leases.sql)
- [V16__sales_snapshot_version.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V16__sales_snapshot_version.sql)
- [V17__coupon_display_snapshot_version.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V17__coupon_display_snapshot_version.sql)
- [V18__event_outbox.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V18__event_outbox.sql)
- [V19__sales_product_name_snapshot.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V19__sales_product_name_snapshot.sql)
- [V20__outbox_worker_claim.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V20__outbox_worker_claim.sql)
- [V21__payment_delivery_requests.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V21__payment_delivery_requests.sql)
- [V100__products.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V100__products.sql)
- [V101__product_sale_control.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V101__product_sale_control.sql)

JPA 매핑: [PaymentDeliveryRequest.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/entity/PaymentDeliveryRequest.java) · [Order.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/entity/Order.java) · [MemberPurchaseGuard.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/entity/MemberPurchaseGuard.java) · [PaymentGroup.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/order/entity/PaymentGroup.java) · [CartItem.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/cart/entity/CartItem.java) · [Sales.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/entity/Sales.java) · [SalesStock.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/entity/SalesStock.java) · [Product.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/entity/Product.java) · [ProductImage.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/entity/ProductImage.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
