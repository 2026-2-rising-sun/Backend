# Cart 모듈 ERD

상태: Cart 구현·이행 검증 완료 · 전체 거래 연계 검증 대기

기준: `refactor/#332-cart-module`, 기존 dev `cc91c09b` 위의 Cart 변경. [HTML 관계도](ERD.html) · [설계](설계.md) · [구현 계획](../../../exec-plans/active/332-cart-module/README.md) · [현행 Core 전체 ERD](../../services/commercecore/erd.md)

상품 하나의 옵션이 달라도 장바구니에서는 SKU마다 수량을 따로 관리한다. 기존 판매정보가 없어 SKU로 연결할 수 없는 항목은 지우지 않고 상품 ID와 수량을 보존하며, 주문 선택에서는 이용 불가로 처리한다. 장바구니에 담거나 조회해도 재고를 예약하지 않는다.

## 관계와 소유권

| 출발 | 도착 | 관계와 소유권 |
|---|---|---|
| Product.id | SKU.product_id | 상품 1 : SKU N. Product/SKU 담당 소유이며 물리 FK 없음 |
| SKU.id | cart_item.sku_id | SKU 1 : Cart 항목 N. 레거시 행은 sku_id가 NULL일 수 있으며 물리 FK 없음 |
| Member 회원 ID | cart_item.member_id | 회원 1 : Cart 항목 N. 다른 서비스와의 ID 참조이며 물리 FK 없음 |
| Product.id | cart_item.product_id | 기존 상품 참조 보존. 신규 SKU 항목도 상품별 묶음·기존 Order 연결을 위해 함께 저장 |
| cart_item.id/version 값 | Order 출처 ID/version | 결제 성공 정리와 과거 환불 식별을 위한 복사값. Cart 삭제 후에도 Order 값은 남으므로 FK 없음 |

Product·SKU·Cart 항목 ID는 BIGINT/Long이다. Order 목표 저장 위치는 Order 담당이 소유하며 이 문서에서 연결 테이블이나 Cart 상태를 추가하지 않는다.

## Cart 물리 필드

| 필드 | 타입·제약 | 의미 |
|---|---|---|
| id | BIGINT IDENTITY PK | 기존 항목 ID를 보존 |
| member_id | VARCHAR(36) NOT NULL | 인증된 회원의 UUID 문자열 |
| product_id | BIGINT NOT NULL, CHECK > 0 | 기존 상품 참조·상품별 묶음 기준 |
| sku_id | BIGINT NULL, CHECK NULL 또는 > 0 | 구매 단위. 기존 매핑 불가 행만 NULL로 보존 |
| quantity | INTEGER NOT NULL, CHECK > 0 | 해당 항목의 절대 수량 |
| version | BIGINT NOT NULL DEFAULT 0 | 변경·재추가와 과거 구매 출처를 구분하는 기존 동시성 값 |
| created_at | TIMESTAMP WITH TIME ZONE NOT NULL | 생성 시각 |
| updated_at | TIMESTAMP WITH TIME ZONE NOT NULL | 수정 시각 |

`UNIQUE(member_id, sku_id)`로 같은 회원의 같은 SKU는 한 행만 존재한다. 레거시 `sku_id IS NULL` 행에는 `UNIQUE(member_id, product_id) WHERE sku_id IS NULL` 부분 유일 인덱스를 두어 기존 중복 방지도 보존한다. 같은 상품의 서로 다른 SKU는 함께 담을 수 있다.

가격·available/reserved·주문 상태·선택 체크·만료 시각은 Cart 테이블의 원본 필드가 아니다. SKU의 현재가와 판매 정보는 조회 시 읽고 주문 금액은 Order 스냅샷을 사용한다.

## V103 확장 이행

1. nullable sku_id를 추가한다.
2. 기존 sales_info.product_id → sales_info.id → V102가 보존한 sku.id로만 연결한다. sku.product_id의 임의 첫 행을 선택하지 않는다.
3. 기존 회원+상품 유일 제약을 회원+SKU 유일성으로 바꾸고 미매핑 레거시 부분 유일 인덱스를 추가한다.
4. Cart의 ID·회원·상품·수량·version·감사 시각과 Order/환불 출처는 바꾸지 않는다. 매핑 없는 항목을 삭제하거나 임의 SKU를 생성하지 않는다.

Core V101 Product → V102 SKU → V103 Cart → V104 Order 순서다. 적용된 SQL은 수정하지 않으며 legacy Sales 쓰기 전환이 끝나기 전 중간 상태를 독립 배포 가능하다고 보지 않는다.

## 성공 정리와 실패 복구

최종 Order 연결은 선택 Cart 행의 ID순 잠금 → Product/SKU 검증·예약 순서다. Cart는 해당 잠금 포트를 제공하며 현재 Cart 브랜치의 Order는 구 Sales 경로가 남아 있다. 완료 처리는 사라지거나 수정된 Cart도 허용하며 원본 ID·회원·version이 같은 행만 삭제한다. 수정되거나 다시 담긴 행과 선택하지 않은 항목은 남긴다. 삭제 SQL 오류는 Order 상태·재고 변경과 함께 롤백한다. 실패·취소·만료에 성공 정리를 수행하지 않는다.

Cart에는 별도 업무 상태 enum이 없으므로 독립 상태관리도는 작성하지 않는다. SKU READY/판매 상태와 Order 결제 상태를 Cart에 복제하지 않는다.

## 코드와 검증 근거

- [CartItem.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/cart/entity/CartItem.java)
- [V103__cart_sku_reference.sql](../../../../services/commercecore-service/src/main/resources/db/migration/V103__cart_sku_reference.sql)
- [CartSelectionPort.java](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/cart/service/CartSelectionPort.java)
- [CartSelectionServiceTest.java](../../../../services/commercecore-service/src/integrationTest/java/com/shoppinglive/commercecore/cart/service/CartSelectionServiceTest.java)

2026-11-13 체크포인트의 Cart 단위13/H2통합20/실제 PostgreSQL12/Flyway3 통과는 당시 결과다. `d85332a9`의 CI는299개 중34실패·2스킵이며, 현재 독립 Cart 검증과 선행 SKU 통합 뒤 결과는 [2026-11-14 복구 기록](../../../worklog/2026-11-14.md#332-cart-복구-검증)에서 확인한다. Cart 원본 YAML은 이전 스키마이고 Order `c3d96569`에 Cart 계약 조각이 통합됐다는 인계를 받았다. 전체 거래·계약 검증은 최종 Order 통합 HEAD에서 수행하며 운영 적용 증거와 구분한다.
