package com.shoppinglive.commerce.shopping.application;

import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import java.util.Optional;

/**
 * shopping-service 의 상품 정보를 조회하기 위한 어댑터 계약.
 *
 * <p>commerce-service 는 이 인터페이스에만 의존하고, 실제 구현체는 다음 두 종류를
 * 준비한다.
 * <ul>
 *   <li><b>InMemoryShoppingClientStub</b> — 개발·통합 테스트용 (Sprint 2 Phase 4)</li>
 *   <li><b>HttpShoppingClient</b> — Spring RestClient + Resilience4j 실제 호출
 *       (Sprint 2 Phase 6, 준제 페어링)</li>
 * </ul>
 *
 * <p>계약 (두 구현체 모두 동일 시맨틱):
 * <ul>
 *   <li>상품이 존재하지 않으면 {@link Optional#empty()} 반환</li>
 *   <li>네트워크·5xx·timeout 은 {@link ShoppingUnavailableException} 을 던진다</li>
 *   <li>동일 요청은 부작용 없이 반복 가능해야 한다 (idempotent read)</li>
 * </ul>
 */
public interface ShoppingClient {

    /**
     * 상품 스냅샷을 조회한다.
     *
     * @param productId shopping-service 상품 식별자
     * @return 존재하면 스냅샷, 없으면 {@link Optional#empty()}
     * @throws ShoppingUnavailableException 일시적 장애 (네트워크·5xx·timeout)
     */
    Optional<ProductSnapshot> findProduct(Long productId);

    /**
     * 상품 존재 여부만 빠르게 확인한다. 기본 구현은 {@link #findProduct(Long)} 위임이며,
     * 실제 HTTP 구현체는 HEAD 요청 등으로 최적화할 수 있다.
     *
     * @param productId shopping-service 상품 식별자
     * @return 존재하면 true
     * @throws ShoppingUnavailableException 일시적 장애
     */
    default boolean exists(Long productId) {
        return findProduct(productId).isPresent();
    }
}
