# Order ERD — 논리 관계와 V104 물리 매핑

[ERD.html](ERD.html) · [상세설계](설계.md) · [상태](상태관리도.md) · [현재 전체 Core ERD](../../services/commercecore/erd.md)

구현 기준은 Core V104·Payment V4다. 전체 통합 검증 상태는 [실행 계획](../../../exec-plans/active/331-order-module/README.md)에 있다. HTML은 논리 Order/OrderItem 명칭을 쓰며 물리 테이블 rename이나 PK 교체를 뜻하지 않는다.

| 논리 개념 | 실제 물리 원본 | 실제 관계·불변조건 |
|---|---|---|
| Order | payment_group | BIGINT PK 유지. 새 group_number는 UUID, 기존 번호 보존. policy_version=1은10분, 버전0은 과거TTL 유지. failure_reason/coupon_order_item_id 추가 |
| OrderItem | orders | payment_group_id로1:N. sku_id, product_id_snapshot, seller_id_snapshot, main_image_snapshot. 생성 시 단가·수량·상품명 보존. 할인액은 결제 원문 저장 전 확정 |
| RefundRequest | refund_request | 한 주문에0:N. 회원/키/원문·금액·상태·purpose(USER/COMPENSATION)·종료 요청 참조. root 잠금으로 미확정 USER 한 건만 허용 |
| RefundItem | refund_target_order | (refund_request_id,order_id) 복합 PK. quantity/refund_amount 고정. 한 구매 항목은 여러 부분 환불에 포함 가능; 성공 수량·금액 합계로 한도 검사 |
| 실행 전달 기록 | payment_delivery_request | 기존 Payment/환불 실행 ID와 요청 UUID·원문·결과·재시도 시각 보존 |
| 종료 전달 기록 | order_payment_closure | closure_request_id PK, payment_request_id/payment_id 각각 유일. 만료 트랜잭션에서 저장 후 HTTP 재전송 |
| 회원 구매 가드 | member_purchase_guard | 기존/신규 API의 활성 구매 제한을 같은 회원 행 잠금으로 직렬화 |
| 쿠폰 사용 출처 | member_coupon | used_payment_group_id를 조건부 대조해 마지막 전량 환불 성공 때만 해당 사용 건 복원. expires_at 변경 없음 |
| SKU | sku, 다른 모듈 | BIGINT sku.id. 현재 price/available/reserved/sale_status 소유. 기존 sales_info.id와 기본 SKU id 매핑 보존 |
| Product | product, 다른 모듈 | 상품 표현/전체 판매허용/소프트 삭제. 신규 구매 검증 때만 Product→SKU 잠금 순서 사용 |

별도 재고 예약 테이블은 없다. Order 상태/환불 적용 기록과 SKU 변경을 같은 DB 트랜잭션에 반영한다. 보상 환불은 사용자 RefundItem이 없으며 이미 만료 해제한 재고를 다시 복구하지 않는다.

새 SKU 항목은 sales_info_id가 없을 수 있어 V104에서 nullable로 확장했다. `sales_info_id IS NOT NULL OR sku_id IS NOT NULL` 거래 출처 제약을 적용한다. 기존 FK와 적용 SQL은 그대로 보존하고 새 모듈 간 FK는 추가하지 않는다. HTML 화살표는 논리 관계이며 모든 선이 물리 FK라는 뜻이 아니다.

옵션 JSON 공급·버전 계약은 보류다. selected_options_snapshot은 현재 null이며 현재 Catalog 옵션을 임의 복사하지 않는다. 별도 checkoutRevision은 만들지 않았고 policy_version/JPA version·영속 결제 원문으로 상태와 금액 고정을 보호한다.

## 이행 검증

V102는 기존 Sales 가격·available/reserved·기본 ID를 그대로 이행한다. V104는 주문 당시 단가/상품명/금액·PK/번호·Cart ID/version·기존15분 기한을 보존하며 검증된 Sales ID→같은 SKU ID만 backfill한다. 과거 옵션·판매자·이미지의 근거 없는 보강은 하지 않는다.

실제 PostgreSQL `OrderSheetMigrationPostgresTest`는 이전 버전 데이터에서V104 적용 후 위 값과 기존 환불 수량 의미 보존을 검사한다. DB 이행 테스트와 업무 서비스의 성공/취소/환불 테스트는 서로 다른 증거로 기록한다.

구 productId API도 신규 SKU 출처(`sku_id`, `product_id_snapshot`)를 저장하고 `sales_info_id`는 null이다. 기존 주문의 Sales 참조·FK는 보존하며 완료·해제·환불은 `sku_id` 우선, 없으면 과거 `sales_info_id`를 사용한다. 상품에 SKU가 여러 개면 구 API가 임의 옵션을 선택하지 않고409로 새 주문서를 안내한다.
