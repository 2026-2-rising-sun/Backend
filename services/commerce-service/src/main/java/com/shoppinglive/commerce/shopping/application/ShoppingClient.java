package com.shoppinglive.commerce.shopping.application;

import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import java.util.Optional;

/**
 * shopping-service 의 상품 정보를 조회하기 위한 어댑터 계약.
 *
 * <p>운영에서는 Spring RestClient + Resilience4j HTTP 구현체를 사용한다.
 * 인메모리 fixture는 테스트 소스에만 존재한다.
 *
 * <p>계약 (두 구현체 모두 동일 시맨틱):
 * <ul>
 *   <li>상품이 존재하지 않으면 {@link Optional#empty()} 반환</li>
 *   <li>네트워크·5xx·timeout 및 응답 계약 위반은 {@link ShoppingUnavailableException} 을 던진다</li>
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
