# Product ERD — 상품 원본과 판매 제어

<!-- visual: relations -->

기준: `cc91c09b` 위 Product #333 구현. Core V101·Catalog V2 SQL과 Java 매핑을 설명한다. 이 문서는 운영 DB 적용 결과가 아니다. SKU는 #334의 제공 포트/목표 관계이며 아직 이 브랜치에 V102가 통합되지 않았다.

[시각 자료](ERD.html)

상품 한 개는 공통 이름·설명·대표 이미지를 가진다. 실제 구매 단위인 SKU에 가격·재고·개별 판매 상태가 속한다. 상품을 통째로 잠시 멈춰도 각각의 SKU가 가진 중지 의도와 수량은 유지한다.

## 테이블

| 테이블 | 핵심 키·필드 | 제약과 구현 근거 |
|---|---|---|
| Core product | id BIGINT PK; name VARCHAR(100); description VARCHAR(2000); main_image_id BIGINT; seller_id VARCHAR(36); category VARCHAR(32); sale_enabled BOOLEAN; deleted_at TIMESTAMPTZ; version BIGINT; 감사시각 | main_image_id NOT NULL·인덱스 유지, idempotency_key UNIQUE 유지. 신규 sale_enabled=false, 기존 행 true 보존. 삭제 시 전체 false CHECK. [V100](../../../../services/commercecore-service/src/main/resources/db/migration/V100__products.sql) · [V101](../../../../services/commercecore-service/src/main/resources/db/migration/V101__product_sale_control.sql) |
| Core product_image | id BIGINT PK; storage_key UNIQUE; content_type; size_bytes; width/height | 상품에 파일을 복사하지 않고 이미지 ID로 참조한다. V101에서 이미지 FK만 제거한다. 상품 삭제/대표 이미지 변경으로 공유 파일을 삭제하지 않는다. |
| Catalog product | Core id PK; 공통 표현·이미지 URL·seller/category; sale_enabled; deleted_at; version; 원본 감사시각 | 사본이며 원본 쓰기 API가 아니다. 더 큰 version만 반영하고 삭제 사실은 보존한다. 구형 이벤트의 생략된 제어 필드는 true/null. [V2](../../../../services/catalog-service/src/main/resources/db/migration/V2__product_sale_control.sql) |
| Core event_outbox | event_id UNIQUE; aggregate_id; payload; 전달·재시도 필드 | 실제 Product 변경과 같은 트랜잭션. 전체 snapshot에 saleEnabled/deletedAt 포함. [생산자](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/event/ProductInfoEventPublisher.java) |
| SKU — #334 통합 목표 | id BIGINT PK; product_id BIGINT; 현재 price; available/reserved; 개별 sale_status; version | Product 한 개에 0..N개. 옵션 없는 실제 판매 단위도 기본 SKU 사용. FK 없음. 이 테이블 SQL/가격·수량 갱신은 SKU 담당 소유다. |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| Core product.main_image_id | Core product_image.id | N:1 논리 참조, 물리 FK 없음. 등록·이미지 교체 때 애플리케이션이 존재 확인. |
| SKU.product_id | Core product.id | N:1 논리 참조의 통합 목표. 상품 선저장 가능. SKU 생성 트랜잭션에서 상품 공유 잠금·존재/본인/미삭제 확인. |
| Core Product 변경 | Core event_outbox | 변경·버전·전체 snapshot을 같은 DB 트랜잭션으로 저장. Outbox 실패 시 변경도 롤백. |
| commerce.product-info.v1 | Catalog product | 비동기 전체 snapshot. Product version으로 중복·역순 보호. 삭제 후 구형 snapshot도 삭제 사실을 해제하지 않음. |
| ProductTradeSnapshot | 신규 Order의 상품 표시 값 | Product ID 정렬 공유 잠금 이후 불변 값 반환. 가격/재고는 포함하지 않음. Order 연결은 #331 소유. |

## 이행과 경계

- Core V101은 기존 상품에 true를 채운 뒤 DB 기본값을 false로 바꾼다. 신규 준비 상태를 기존 상품에 일괄 적용하지 않는다. 기존 데이터 삭제/추정 seller backfill은 하지 않는다.
- 삭제된 상품은 일반 내부/판매자 조회와 Catalog 공개 조회에서 제외한다. 전체 중지와 공개 여부는 별개다. 기존 공개 조건을 유지하며 전체 중지 상품의 구매만 막는다.
- FK 제거는 NOT NULL·UNIQUE·CHECK나 상품/이미지 검증 제거가 아니다. SKU 행·이미지·주문 snapshot을 cascade 삭제하지 않는다.
- 옵션 비정형 JSON/Catalog 상세 관리·조합 중복·변경 연계는 보류한 FigJam 이슈다. 임의 옵션 테이블/JSON API를 추가하지 않는다.

## 원본과 검증

- [Product 엔티티](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/entity/Product.java) · [거래 검증 포트](../../../../services/commercecore-service/src/main/java/com/shoppinglive/commercecore/product/service/ProductTradingService.java)
- [상품 이벤트 계약](../../../../contracts/events/commerce-product-info.yaml) · [Catalog 적용](../../../../services/catalog-service/src/main/java/com/shoppinglive/catalog/product/repository/ProductReplicaRepository.java)
- [현재 서비스 ERD](../../services/commercecore/erd.md) · [상태 원본](상태관리도.md) · [실제 실행 기록](../../../worklog/2026-11-13.md)

테스트 경로와 실제 통과는 구분한다. H2는 업무·원자성 회귀, PostgreSQL은 공유 잠금/경합·V101/V2 이행 증거다. HTML은 설명 자료이며 DB·브로커 실행 증거가 아니다.
