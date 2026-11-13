# Product 구현 경계와 통합 대상

상태: Product #333 구현 중. 물리 모델/상태 원본은 [ERD](../../../design-docs/commercecore/product/ERD.md)·[상태](../../../design-docs/commercecore/product/상태관리도.md), 채택 이유는 [ADR](../../../adr/0333-product-sku-boundary.md)을 따른다. 아래 signature는 실제 Java와 일치한다. 공유 계약·SKU/Order 연결은 후속 통합 대상이다.

## 거래 포트

공급: `com.shoppinglive.commercecore.product.service.ProductTradingService`. 클래스의 `@Transactional(propagation = MANDATORY)`로 호출자 트랜잭션이 없으면 거절한다.

| 메서드 | 반환 | 검증/잠금 |
|---|---|---|
| lockForNewOrder(Collection<Long> productIds) | List<ProductTradeSnapshot> | 필수·양수 ID, 중복 제거·오름차순 공유 잠금. 모든 상품이 존재하고 미삭제·전체 허용이어야 함 |
| lockForSkuCreation(Long productId, String sellerId) | ProductTradeSnapshot | 공유 잠금, 존재·본인·미삭제. 전체 중지 중 준비용 SKU 추가 허용 |

DTO: `com.shoppinglive.commercecore.product.dto.ProductTradeSnapshot(Long productId, String name, String sellerId, String mainImageUrl, long version)`. 가격/재고가 없는 불변 record이며 JPA 엔티티를 반환하지 않는다.

EntityManager.refresh(PESSIMISTIC_READ)로 공유 잠금과 재조회를 함께 수행한다. 잠금 조회를 먼저 하면 앞선 일반 조회의 캐시된 옛 version 때문에 최신 상태 검사 전에 낙관적 충돌이 날 수 있어 refresh 단계에서 잠근다. PostgreSQL에서 공유 잠금끼리 공존하고 UPDATE/삭제와 경합하는지 검증한다. H2 실행을 PostgreSQL 공유 잠금의 증거로 삼지 않는다.

Order는 모든 Product를 먼저 처리한 뒤 모든 SKU를 ID 순으로 잠근다. 외부 결제/브로커 대기를 잠금 구간에 넣지 않는다. 기존 주문의 완료/해제/환불 복구에 이 신규 거래 가드를 재사용하지 않는다.

## 일반 조회

ProductLookup.findProduct(Long)·findProducts(Collection<Long>) signature는 유지한다. `ProductSnapshot(Long id, String name, String mainImageUrl, String sellerId, boolean saleEnabled)`에 전체 허용을 추가했다. 삭제 상품은 Optional.empty/일괄 결과에서 제외하고 표시용으로 거래 잠금을 사용하지 않는다.

기존 3/4인자 생성자는 레거시 fixture용 true 호환 기본값을 유지한다. 실제 ProductQueryService는 원본 false/true를 명시한다. 일반 조회의 누락 제외를 신규 주문의 전원 유효성 검증 대용으로 쓰지 않는다.

## 공유 책임과 의존

| 대상 | 소유자 | 연결 순서 |
|---|---|---|
| Product Java/검증·V101/Catalog V2·Product 이벤트 | Product #333 | 기반 커밋 선행 공유 |
| SKU V102/Catalog V3·가격/수량/개별 상태·sales-info | SKU #334 | Product 기반 merge 후 실제 포트 연결 |
| Core YAML 최종 편집 | Order #331 | api-changes.yaml의 실제 경로/DTO 조각을 의미 보존해 통합 |
| Catalog API 판매/SKU | SKU #334 | catalog-api-changes.yaml의 SALE_SUSPENDED 통합 |
| Cart/Order | 각 담당 | Cart는 일반 ProductSnapshot, 신규 Order만 ProductTradingService 사용 |
| FigJam API/ERD/상태도 | 코디네이터 | 확정 API/필드/전이 차이를 근거와 전달 |

SKU 제공 signature는 `findBySkuIds(Collection<Long>)` / `findByProductId(Long)` → List<SkuView>다. Product는 검증된 SKU 커밋을 받기 전에 타입을 복제하지 않고 상품별 목록 연결을 기다린다. 대표 SKU 임의 선택·최저가/합산재고 정책은 도입하지 않는다.

## 남은 통합

공통 API 원본과 HTML, 기존 단일 판매 응답의 다중 SKU 호환, 전체 잠금 순서, 검증 SHA merge, 외부 환경 검증을 마친다. PR base는 Product→dev, SKU→Product, Cart→SKU, Order→Cart다. 기존 적용 SQL은 바꾸지 않고 데이터 삭제·추정 backfill을 하지 않는다.

구현 경계는 원본 코드/DDL/계약과 함께 갱신한다. 과거 설계 초안은 로컬 설계 인계 이력에 보존하고 현재 정책 복제본을 따로 편집하지 않는다. [API 변경 조각](api-changes.md)·[작업 기록](../../../worklog/2026-11-13.md)을 따른다.
