# Order ERD 설명 — 목표 모델 초안

상태: 구현 진행 중. [상세설계](설계.md)의 논리 모델을 [ERD.html](ERD.html)로 표현했다. 실제 DB는 `cc91c09b753a12a352bda65481210e264d3e88b0`의 마이그레이션·엔티티이며 아래 표는 아직 적용하지 않은 물리 매핑 검토안이다.

## 현재와 목표의 구분

현재 구매 묶음은 `payment_group`, 구매 항목은 `orders`다. 목표 Order는 구매 묶음, OrderItem은 SKU 구매 항목이다. 이름을 바꾼다는 이유로 기존 식별자를 교환하거나 결제·환불 참조를 다시 발급하지 않는다.

초기 이행은 **물리 테이블명과 PK를 유지하고 필요한 열·제약을 확장**하는 안을 권장한다. Java 모델·API 명칭과 물리 테이블명이 일시적으로 다르다는 비용은 있지만 외부 Payment 참조와 환불 이력을 보존하기 쉽다. 별도 신규 테이블 복사/이중 쓰기, 즉시 테이블 rename은 아직 채택하지 않았다. 이 물리명/PK 보존 확장안은 착수 지시에서 채택됐다. 새 주문서는 정책 버전 1, 기존 활성 구매는 버전 0으로 원래 TTL/처리 경로를 유지한다.

| 목표 개념 | 기존 물리 원본 | 검토할 변경 | 소유 제안 |
|---|---|---|---|
| Order | `payment_group` | 주문 실패 사유, 결제 금액 고정 여부/시각·revision, 부분/전체 환불 상태, 쿠폰 복원 적용 관계 | Order |
| OrderItem | `orders` | `sku_id`, product/seller/옵션 표시 스냅샷, 선택 쿠폰 적용 항목. 원가와 수량은 생성 후 불변, 할인액은 결제 요청 저장 전까지 변경 가능 | Order |
| RefundRequest | `refund_request` | 기존 요청 키·금액·상태를 유지하고 미확정 요청 유일성 및 결과 적용 경계 보강 | Order |
| RefundItem | `refund_target_order` | `quantity` 추가, 같은 구매 항목을 여러 환불 요청에서 참조 가능. 성공 요청의 수량·금액만 합산 | Order |
| Payment 전달 | `payment_delivery_request` | 기존 주문/묶음/환불 ID의 의미 보존. 종료 통지·보상 식별 방법은 별도 계약 검토 | Order 연계 담당 배정 필요 |
| 구매 가드 | `member_purchase_guard` | 회원당 활성 주문 제한을 보존한다면 기존 잠금 행 재사용 | Order |
| 쿠폰 사용 | 기존 쿠폰/예약 기록 | 해당 주문 사용 건만 복원하는 식별·적용 기록 검토. 새 사용 건을 과거 환불로 해제하지 않음 | Order의 필요한 쿠폰 연계 범위만 |
| SKU / Product | SKU 담당 모델 / `product` | 현재 가격·재고는 SKU, 상품 표현·전체 판매허용은 Product. Order가 이 테이블 DDL을 중복 작성하지 않음 | SKU / Product |

## 관계

| 원본 | 대상 | 카디널리티·의미 |
|---|---|---|
| Order | OrderItem | 1 : 1..N. 한 주문번호 아래 구매 항목 |
| Order | RefundRequest | 1 : 0..N. 서로 다른 환불 건 구분 |
| RefundRequest | RefundItem | 1 : 1..N. 한 요청의 항목·수량·접수 시 확정 금액 |
| OrderItem | RefundItem | 1 : 0..N. 성공한 수량의 합이 구매 수량 이하 |
| OrderItem | SKU | N : 1 논리 식별. 현재 SKU 값으로 주문 스냅샷을 다시 구성하지 않음 |
| SKU | Product | N : 1 논리 식별. 상품의 여러 옵션 조합 |

그림의 선은 물리 FK 생성 지시가 아니다. Product–SKU FK 미사용 방향은 보존하며 기존 주문/환불/전달 FK 제거·유지는 별도 이행 결정이다. 독립 Payment DB에 FK나 JOIN을 추가하지 않는다.

## 형식·제약 후보

- 기존 주문·항목·환불 PK는 BIGINT, 회원 식별은 VARCHAR(36), 주문번호/요청 키는 최대 64자, 시각은 TIMESTAMPTZ를 유지하는 안이다. 2026-11-13 코디네이터 1차 조정으로 **Product·Cart 항목·SKU ID는 BIGINT/Java Long**이며 기존 Sales ID를 기본 SKU ID에 보존하는 이행을 우선한다. 이 조정이 Order의 ID/주문번호 이행 전체를 확정한 것은 아니다.
- 금액은 KRW 원 단위 BIGINT, 수량은 양의 INTEGER. 금액은 0 이상이며 할인액은 원가 이하다. 곱셈/합산의 overflow를 검사한다. 실제 지불액 0원 항목도 구매/환불 수량 집계에 포함한다.
- 옵션 표시 스냅샷은 비정형 JSON을 저장하는 안이다. Catalog 원본의 제공·검증·버전 경계를 먼저 합의하고, 생성 시 외부 HTTP를 DB 잠금 안에서 호출하는 설계를 피한다. JSON은 현재 가격·재고의 원본을 대체하지 않는다.
- Order의 `(member_id, creation_key)` 및 주문번호, RefundRequest의 `(member_id, idempotency_key)`, RefundItem의 `(refund_request_id, order_item_id)`가 고유해야 한다. 실제 열 이름은 기존 키/인덱스를 재사용할 수 있다.
- Order 합계, 성공 환불 합계, 다른 행의 수량 한도는 Order 잠금 아래 검사한다. CHECK 제약만으로 여러 행의 불변조건을 보장한다고 설명하지 않는다.
- 만료 조회는 상태/기한/ID, 구매 내역은 회원/생성시각/ID, 환불 집계는 구매 항목 ID 인덱스를 사용한다. 기존 인덱스와 중복 생성하지 않는다.

## 이행과 검증

기존 항목의 단가·할인·지불액은 그대로 보존한다. 과거 옵션은 근거 없이 현재 값으로 채우지 않는다. 기존 성공 환불의 quantity는 그 당시 항목 전량이라는 현행 의미를 검증한 뒤 backfill하는 안이다. 진행 중 주문/환불은 임의 전량 성공 처리하거나 TTL을 새로 부여하지 않는다.

신규 Product false/SKU READY의 판매 준비 기본값은 신규 생성에만 적용하고 기존 상품/SKU 상태는 보존한다. #340 보류 중 현행 주문의 Cart ID/version과 환불 참조도 유지하며 목표 모델의 보존 위치·호환을 정한 뒤에만 이행한다. Sales ID→기본 SKU ID에 충돌/매핑 불가가 있으면 근거를 기록하고 코디네이터와 재조정한다.

[실행 계획](../../../exec-plans/active/331-order-module/README.md)의 순서와 실제 PostgreSQL migration 검증으로 번호·금액·참조·중복 처리 보존을 확인해야 한다. DDL은 Core V104, 독립 Payment V4가 배정됐으며 Product→SKU→Cart→Order 순으로 검증 커밋을 통합한다.

## 시각 자료의 상태

기존 FigJam 스타일을 선택한 사용자 답변에 따라 HTML 초안을 만들었고 데스크톱·390px 화면을 확인했다. 착수 후 MD 링크·최신 기준·확정/미결정 표시를 수정하고 전체 보기/확대를 추가해 실제 화면을 다시 확인한다. 같은 이름의 이 MD가 관계·제약 설명을 제공한다. 저장소의 `services/commercecore/erd.md`와 생성 HTML은 **현재 구현 설명**이므로 목표 모델로 미리 덮어쓰지 않았다. 구현 단계에는 해당 원본 MD를 함께 갱신하고 `scripts/render_design_docs.py`로 그 생성 HTML을 재생성한다.
