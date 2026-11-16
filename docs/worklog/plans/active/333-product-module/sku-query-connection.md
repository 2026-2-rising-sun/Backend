# Product 조회의 SKU 연결 조각 — #334 적용 대상

코디네이터 배정에 따라 Product #333이 SKU를 역merge하지 않고 조각과 검증 기준을 제공한다. 이 문서는 #334에서 적용·컴파일·통합 테스트할 변경이다. #333 작업 트리에서 SKU 연결이 실행됐다고 보고하지 않는다.

## 실제 제공 포트와 내부 목록 조각

검증 커밋 `0b36ba4722aea963b092e87a1f6af4145e920af3`의 `com.shoppinglive.commercecore.sku.service.SkuService.findByProductId(Long)`는 `List<SkuView>`를 반환한다. `SkuView`는 skuId/productId/price/available/reserved/saleStatus/version이며 `status()`는 기존 SalesStatus 호환 표시다. 이 타입/SQL을 Product에서 복제하지 않는다.

`SellerProductQueryService`에 실제 SkuService를 생성자 주입하고 다음 내부 조회를 추가한다. 본인·미삭제 검증은 기존 get의 상품 검증과 같은 private 메서드로 묶을 수 있다. 표시용 조회에 거래 공유 잠금을 사용하지 않는다.

```java
@Transactional(readOnly = true)
public List<SkuView> listSkus(Long productId, String sellerId) {
    productRepository.findById(productId)
            .filter(product -> sellerId.equals(product.getSellerId()))
            .filter(product -> !product.isDeleted())
            .orElseThrow(() -> new BusinessException(
                    ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + productId));
    return skus.findByProductId(productId);
}
```

`skus`의 실제 타입은 `com.shoppinglive.commercecore.sku.service.SkuService`, 반환 타입은 `com.shoppinglive.commercecore.sku.dto.SkuView`다. 준비/전체 중지 상품도 본인에게 목록을 제공하고 SKU 0개면 빈 목록을 반환한다. 새 HTTP 경로나 대표 가격 정책은 이 조각에 포함하지 않는다.

## 기존 판매자 페이지를 전환할 때

- 기존 `SalesJpaRepository.findLookupsByProductIds`와 Sales/Stock 직접 조인은 SKU 쓰기 전환과 함께 바꿔야 한다. 가격·수량을 SKU에 쓰면서 Product 조회만 옛 Sales를 읽는 중간 상태를 최종 완료로 보고하지 않는다.
- 페이지는 Product를 먼저 자르고 해당 productIds의 SKU를 한 번에 읽어 `Map<Long,List<SkuView>>`로 묶는다. product마다 findByProductId를 반복하는 N+1을 도입하지 않는다. 실제 SKU 소유자에게 `findByProductIds(Collection<Long>)` 일괄 포트 추가를 요청했고 소유자가 구현한 signature/커밋에 맞춘다. 소비자가 임시 포트나 SKU 테이블 매핑을 복제하지 않는다.
- 전체 건수/offset은 SKU 수가 아니라 Product 수다. status 조건은 페이지 계산 전에 적용한다. NOT_REGISTERED는 SKU 미존재 조건이며 SOLD_OUT은 실제 SKU 호환 표시 규칙을 따른다. 상품 전체 saleEnabled와 SKU 상태를 서로 덮어쓰지 않는다.
- 기존 단일 응답은 SKU 0/1개 경로를 보존한다. 2개 이상에서 임의 첫 SKU/최저가/합산재고/대표 판매 상태를 만들지 않는다. 공개 다중 SKU 응답은 미확정이므로 내부 목록과 구분한다. 기존 단일 응답의 다중 SKU 거절 방식은 SKU의 productId 호환 어댑터와 같은 오류 기준으로 통일하고 실제 오류/응답 조각을 Order 최종 YAML에 전달한다.
- Product API에 saleEnabled가 이미 추가됐으므로 새 SKU 연결에서 이 필드와 삭제 제외를 누락하지 않는다. 등록·수정 응답의 deletedAt도 유지한다.

## 적용 파일과 검증 기준

| 대상 | 실제 검증할 동작 |
|---|---|
| SellerProductQueryService | 본인·미삭제 목록, 준비/중지 중에도 실제 SKU 0..N개 조회, 타인/삭제 404 |
| SellerProductCatalogQueryService | 여러 SKU여도 상품 1행·정확한 total/page, 필터 전후 페이지 일치, SKU 0개/다수 경계 |
| 실제 SKU 쓰기→Product 읽기 | changePrice/adjustAvailable/changeSaleStatus 뒤 기존 단일 응답과 내부 목록이 같은 최신 원본을 반환 |
| ProductHttpTestApplication·ProductPersistenceTest 준비 | 실제 SkuService/필요 설정을 import하고 SKU 테이블을 준비. 옛 Sales fixture만 만들고 최신 SKU를 읽었다고 주장하지 않음 |
| 삭제/기존 주문 수량 | 새 거래 가드는 거절하되 같은 DB 트랜잭션의 SKU consume/release/restock은 전체 중지·삭제에 막히지 않음. 멱등성은 Order 전이/환불 적용 기록의 검증과 연결 |
| 회귀 | Product HTTP/권한/이미지/Outbox/일반 snapshot의 기존 검증문 보존, 가격·재고 타입은 실제 SkuView 사용 |
| 문서/계약 | SKU 연결이 반영된 SHA·실행 환경·건수 공유. 단일 응답 호환/공통 API는 Order·SKU 소유 원본에 함께 반영 |

현재 #333에는 Product 전체 제어 이후 레거시 예약의 소진·해제·복구 가능성을 검증하는 통합 시나리오가 있다. 이는 신규 SKU 경로 실행 증거가 아니며 위 #334 실제 포트 시나리오를 대체하지 않는다.
