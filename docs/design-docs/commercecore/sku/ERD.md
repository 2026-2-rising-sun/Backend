# SKU 데이터 모델

<!-- visual: relations -->

상태: #334 SKU 저장·내부 거래 포트와 기존 단일 판매 API 전환을 반영한다. Product `729d2d5f` 통합 기준이다. V102/V3 실제 PostgreSQL 이행과 SKU 업무·Outbox 롤백을 검증했다. 공개 다중 SKU 흐름과 Order/Cart 최종 통합은 별도다.

[시각 자료](ERD.html)

## 테이블

| 테이블 | 키·필드 | 소유·현재 상태 |
|---|---|---|
| sku | id BIGINT identity PK, product_id BIGINT, price BIGINT>0, available/reserved INTEGER>=0, sale_status, version, snapshot_version>0, product_name_snapshot, created_at/updated_at | SKU 원본. 상품별 여러 행 허용, FK 없음. 신규 READY·reserved=0. 생성·가격·수량·기존 단일 판매 API와 Product 조회 연결. |
| product_sales | sales_id BIGINT PK, product_id BIGINT, price, status, available, version, updated_at | Catalog 판매 사본. V3에서 product_id PK를 SKU 식별자인 sales_id PK로 전환. 기존 필드명·값 보존. |
| product | id BIGINT, sale_enabled, 삭제 정보, 판매자·표현 정보 | Product 소유 목표. 신규 sale_enabled=false, 기존 값 보존. SKU가 이 상태를 중복 소유하지 않음. |
| Order 항목 | sku_id BIGINT, 수량, 주문 당시 단가·상품 정보 | Order 소유 목표. PK·테이블 이행 상세는 Order 구현에 연결. 별도 재고 예약 테이블은 없음. |
| sales_info / sales_stock | 기존 Sales ID·가격·상태와 별도 재고 | 기존 이력·Order 전환 전 호환 경로. V102의 입력이며 삭제하지 않음. 신규 SKU 원본과 병행 쓰는 운영 상태가 아님. |

## 관계

| 출발 | 도착 | 조건·의미 |
|---|---|---|
| Product 1 | SKU 0..N | product_id 논리 참조. 상품 선저장 허용, 옵션 없는 판매 단위도 기본 SKU 필요. FK·상품당 단일 SKU UNIQUE 없음. |
| 기존 Sales 1 | 기본 SKU 1 | sales_info.id를 sku.id로 보존하고 재고·가격·버전을 백필. Cart 이행은 product_id만으로 SKU 전체를 JOIN하지 않음. |
| SKU 1 | Order 항목 0..N | 거래 식별자와 당시 단가/수량을 보존. 현재가 변경으로 기존 주문 금액을 바꾸지 않음. |
| SKU 1 | Catalog 판매 사본 1 | sales-info의 salesId는 SKU ID의 호환 이름. 버전은 SKU별 비교, productId가 같아도 다른 SKU를 덮어쓰지 않음. |

## 필드와 불변조건

| 대상 | 조건 | 구현·검토 상태 |
|---|---|---|
| 금액 | KRW 1원 단위 양의 정수 | price BIGINT, 1원 이상. 향후 환불/배분의 정밀도·잔여 원 규칙은 재검토. 가격 이력/예약 가격 추가 없음. |
| 재고 | available>=0, reserved>=0 | 조건부 UPDATE, 합산/증감은 BIGINT로 범위를 확인해 INTEGER 오버플로 거절. |
| 판매 의도 | READY/ON_SALE/PRIVATE | READY는 생성 전용. ON_SALE+available=0이면 외부 SOLD_OUT 표시. |
| 이벤트 버전 | snapshot_version>0 | 최종 표시 가격·available·상태가 바뀐 커밋에서 증가. reserved만 소진하면 표시 이벤트 없음. DB version과 구분. |
| 이름 사본 | product_name_snapshot | 기존 sales-info 호환용 등록 시 이름 사본. 상품 표현정보의 원본은 Product. |
| 조회 인덱스 | sku(product_id,id), product_sales(product_id,sales_id) | 상품별 SKU를 정렬 조회. 상품당 대표 가격·합산 재고는 저장하지 않음. |

## 근거와 검증 범위

- [Core V102](../../../../services/commercecore-service/src/main/resources/db/migration/V102__sku.sql)
- [Catalog V3](../../../../services/catalog-service/src/main/resources/db/migration/V3__sku_sales_replica.sql)
- [SkuRepository](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sku/repository/SkuRepository.java)
- [Catalog 사본 저장](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/sales/repository/ProductSalesRepository.java)
- [실행 계획과 실제 검증 기록](../../../exec-plans/active/334-sku-module/README.md)

H2 업무 테스트와 Catalog H2 이행 테스트를 실제 PostgreSQL의 잠금·시퀀스·트랜잭션 DDL 증거로 확대하지 않는다. 기존 단일 SKU API는 새 원본으로 연결했다. 공개 다중 SKU·대표가·가격순·Live 다중 SKU 활성화는 보류다.
