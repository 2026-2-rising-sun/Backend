# ADR 0333: Product 표현 정보와 전체 판매 제어

결정 상태: 아래 업무 정책과 신규 판매 초기값은 사용자 채택, 호출/이행 세부안은 통합 검토 중.

구현 상태: 2026-11-13 사용자 후속 구현 지시로 착수. Product 독립 구현·회귀를 진행하며 공유 계약/SKU·Order 연결과 외부 검증은 별도로 확인한다. [실행 계획](../exec-plans/active/333-product-module/README.md) · [실제 포트/통합 대상](../exec-plans/active/333-product-module/model.md) · [작업 기록](../worklog/2026-11-13.md)

관련 이슈: [Product #333](https://github.com/2026-2-rising-sun/Backend/issues/333). 최종 담당은 Order #331·Cart #332·Product #333·SKU #334의 네 PR이다.

## 문제와 현행 근거

현행 Product는 이름·설명·대표 이미지·판매자를 보관한다. Sales는 상품당 한 행이며 가격과 상태를, SalesStock은 판매정보당 한 행의 재고를 가진다. Product 대표 이미지는 DB FK로 연결되고 Product 전체 판매 스위치와 소프트 삭제 필드는 없다. 이는 현재 코드이며 아래 채택 모델이 이미 구현됐다는 뜻이 아니다.

근거: [Product.java](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/entity/Product.java), [V100](../../services/commercecore-service/src/main/resources/db/migration/V100__products.sql), [Sales.java](../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/sales/entity/Sales.java), [V1](../../services/commercecore-service/src/main/resources/db/migration/V1__commerce_baseline.sql). 조사 후 `cc91c09b`로 동기화했으며 해당 업무 코드/계약은 초기 `e5a1b66b`와 같다.

## 채택한 결정과 근거

| 결정 | 사용자 근거와 의미 |
|---|---|
| Product는 상품 표현 정보를 묶는 단위 | 사용자가 이미지·이름·상품설명과 SKU 구성을 설명했다. 같은 수납선반의 블랙/L과 화이트/S는 같은 상품 아래 다른 SKU다. 첨부 화면은 모델 설명 사례이며 쿠팡 내부 DB를 검증한 자료는 아니다. |
| 가격·재고는 SKU 한 테이블/모듈 | 옵션 조합별 구매 단위에 현재가와 available/reserved를 둔다. Product에 가격·재고 원본을 중복 저장하지 않는다. SKU 상세 구현은 #334가 맡는다. |
| 상품 선저장과 완성된 SKU 생성 분리 | 사용자가 “상품만 먼저 저장하는 방식이 유연성” 있다고 선택했다. 이후 SKU 생성 시 가격·초기재고를 “필수로 받아야할듯”이라고 답했다. 재고 0은 허용하며 옵션 없는 상품도 기본 SKU가 필요하다. |
| Product 전체 스위치 + SKU 개별 판매 상태 | 사용자가 “좋아 재고가 없는거랑 상품을 잠시 판매중지하는건 다른 문제니까”라고 두 단계 제어를 수락했다. 전체 중지/재개 때 SKU 상태는 유지하며 Product 품절 상태는 따로 저장하지 않는다. |
| 신규 등록은 판매 준비 상태 | 코디네이터가 전달한 2026-11-13 사용자 답변은 “판매 준비 상태로 생성하고 판매자가 판매 시작을 선택”이다. 신규 Product는 sale_enabled=false, SKU는 READY로 생성하고 기존 상품 상태는 보존한다. READY로 수동 복귀하는 정책은 추가하지 않는다. |
| FK 미사용 | Product 대표 이미지 관계까지 FK를 사용하지 않기로 선택했다. 프로젝트의 선택이며 모든 실무가 FK를 쓰지 않는다는 일반 법칙은 아니다. 논리 참조와 NOT NULL/UNIQUE/CHECK 검증은 유지한다. |
| 상품 소프트 삭제와 주문 독립성 | 원본 Product 정책의 소프트 삭제를 유지하고 주문이 당시 표시/금액을 스냅샷으로 저장한다. 이후 상품 수정이 기존 주문을 바꾸지 않는다. 실물 재고 오차 보정 업무는 제외한다. |
| 옵션 JSON/Catalog 방향 | 사용자가 “json으로 비정형 처리하고 카탈로그가 그거 관리”하는 방향을 지정했다. 세부 구조·검증·변경 연계는 GitHub 이슈 대신 FigJam 설계 이슈로 보류했다. |
| 로컬 데이터 이행 | 현재 데이터는 로컬에만 있으므로 보존 비용이 크면 초기화할 수 있다. 이 허용을 원격/다른 담당자 데이터 삭제로 확대하지 않는다. |

SKU 채팅에서 확정되어 전달된 정책도 보존한다. 별도 예약 테이블 없이 Order 상태 전이에 성공한 처리만 SKU 수량을 바꾸고 같은 DB 트랜잭션에 커밋한다. 주문서 화면 생성 때 주문과 서버 현재 SKU 가격 스냅샷을 저장한다. 결제 완료 주문은 환불 성공 때 취소가 확정되며 중복 복구하지 않는다. Product 채팅에는 이 생성 시점과 충돌하는 별도 합의가 없다.

## 대안과 선택 이유

- SKU 상태를 전체 중지 상태로 일괄 덮어쓰는 안은 재개 시 원래 개별 중지였던 SKU를 구분할 이전 상태/중지 사유가 필요하다. Product 한 행의 전체 스위치를 두면 개별 SKU 제어 의도를 보존하고 중지 중 새 SKU가 추가돼도 전체 구매 차단을 적용할 수 있다.
- Product에 품절 상태도 저장하는 안은 SKU별 재고가 달라질 때 집계 상태 동기화가 필요하다. 전체 제어와 SKU 조건을 함께 읽고 실제 구매 가능 여부를 계산하는 방식을 채택했다.
- 별도 옵션 종류/값/연결 테이블은 현재 도입하지 않는다. 비정형 JSON 방향을 선택했으며 Catalog 상세 모델을 Product PR에 자동 포함하지 않는다.
- FK 제거는 DB의 참조 무결성 검사를 줄이는 대신 애플리케이션 검증·삭제 경계·동시성 검증을 필요로 한다. FK를 없애면서 이미지/상품 존재 검증까지 제거하지 않는다.

## 영향과 남은 검토

Product에 전체 판매 스위치와 삭제 사실이 생기고, 주문은 상품 전체 제어와 SKU 상태/수량을 함께 확인해야 한다. Product 전체 중지·삭제와 신규 주문 사이 경쟁은 공유 잠금/같은 트랜잭션 경계로 정렬하는 기술안을 제안했다. SKU 일괄 상태 갱신은 하지 않는다.

2026-11-13 코디네이터의 통합 경계 1차 결정(`msg_8d6394dae043`)은 업무 정책과 구분한다. Product/Cart/SKU ID는 BIGINT/Long 유지, 공유 API YAML 최종 편집자는 Order #331, Product 제어/삭제 이벤트와 Catalog Product 사본의 최소 호환 변경은 Product #333에 배정됐다. sales-info/다중 SKU 판매 사본은 SKU #334가 맡고 기존 Sales ID를 기본 SKU ID로 보존하는 이행을 우선한다. 옵션 JSON 상세 보류는 유지한다.

신규 판매 초기값은 후속 사용자 답변(`msg_807453dc2818`)으로 해소됐다. 구현 착수 Task에서 sale-state PATCH 채택, Core V101/Catalog V2, Product→SKU→Cart→Order 의존 순서를 배정했다. DELETE는 필수 version 쿼리·204로 구현하며 삭제 복구 API는 추가하지 않는다. 공유 API/외부 단일 판매 응답의 다중 SKU 호환은 실제 담당 조각 통합으로 확인한다. 일반 메서드/DTO/버전 전달 기술 선택을 새 사용자 정책으로 기록하지 않는다.

## 명세와 시각 자료

- [Product 원본 spec](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=118-756)
- [전체 판매 제어 메모](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=465-1315)
- [옵션 JSON/Catalog 후속 이슈](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=448-1280)
- [SKU 원본 spec](https://www.figma.com/board/1lr73O8XYEhMGHAgWAEeOv/?node-id=424-1279)
- [현재 구현 ERD](../design-docs/services/commercecore/erd.md) · [현재 구현 HTML](../design-docs/services/commercecore/erd.html)

서비스 ERD/판매 상태 HTML은 레거시 Sales 관계를 보존하면서 Product V101/V2 변경을 반영한다. [Product 모듈 ERD/상태](../design-docs/commercecore/product/README.md)는 MD 원본에서 생성한 HTML과 연결하며 실제 구현·SKU 후속 목표·실행 검증을 구분한다. FigJam 편집은 코디네이터가 인수했으므로 Product 담당은 API·ERD·상태 차이와 근거만 전달한다.
