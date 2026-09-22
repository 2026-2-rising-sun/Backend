package com.shoppinglive.shopping.sales.application;

import com.shoppinglive.shopping.sales.domain.SalesInfo;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * commerce-service 판매정보 조회 어댑터 계약. {@code shopping.sales-client.mode} 로 구현체를 고른다
 * ({@code stub}: 인메모리, 기본값 / {@code http}: Commerce {@code GET /v1/sales}).
 *
 * <p>모든 구현체가 지키는 계약:
 * <ul>
 *   <li>판매정보가 없는 상품은 결과에서 빠진다 (오류 아님).</li>
 *   <li>빈 입력은 원격 호출 없이 빈 Map. 중복 id 허용.</li>
 *   <li>판매정보를 알 수 없으면 {@link SalesInfoUnavailableException}. 일부만 성공한 결과는 돌려주지 않는다.</li>
 *   <li>부작용 없는 조회 (idempotent).</li>
 * </ul>
 */
public interface SalesInfoClient {

    /**
     * 목록 화면의 N+1 호출을 막는 벌크 조회.
     *
     * @throws IllegalArgumentException 입력이나 원소가 {@code null}
     */
    Map<Long, SalesInfo> findByProductIds(Collection<Long> productIds);

    default Optional<SalesInfo> findByProductId(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("productId must not be null");
        }
        return Optional.ofNullable(findByProductIds(List.of(productId)).get(productId));
    }
}
