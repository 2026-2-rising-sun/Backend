package com.shoppinglive.shopping.coupon.application;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품별 쿠폰 목록을 짧게 캐시한다. 키는 상품 id 이고, 캐시는 Pod 마다 따로 있다 (분산 캐시 아님).
 * 목록은 표시용이다. 쿠폰을 실제로 쓸 수 있는지와 할인 금액은 주문 시점에 Commerce 가 판정한다.
 *
 * <ul>
 *   <li>첫 조회: 캐시에 없으면 Commerce 를 조회한다. 같은 상품을 동시에 조회해도 호출은 한 번이고 나머지는 그 결과를 기다린다.</li>
 *   <li>갱신: 저장 뒤 {@code cacheTtl} 이 지난 항목은 다음 조회 때 백그라운드 갱신을 한 번 시작한다.
 *       갱신이 끝날 때까지 모든 요청은 이전 값을 바로 받는다(stale-while-refresh).</li>
 *   <li>만료: 저장 뒤 {@code MAX_STALE} 이 지나도록 갱신되지 않은 항목은 버리고, 다음 조회가 첫 조회처럼 기다린다.</li>
 *   <li>실패: 빈 목록을 같은 방식으로 캐시한다. Commerce 장애 중에도 상품당 호출이 {@code cacheTtl} 마다 한 번으로 줄어든다.</li>
 * </ul>
 *
 * <p>오래된 값의 범위: 계속 조회되는 상품은 {@code cacheTtl} 에 갱신 조회 시간을 더한 만큼, 한동안 조회가 없던 상품은
 * 첫 요청이 이전 값을 받으므로 최대 {@code MAX_STALE} 까지 늦을 수 있다.
 * 갱신이 도는 중에 항목이 만료되면 같은 상품 조회가 두 번 나갈 수 있다(HTTP timeout 으로 기간이 제한된다).
 */
@Slf4j
public class ProductCouponService {

    private static final int MAX_ENTRIES = 10_000;
    private static final Duration MAX_STALE = Duration.ofMinutes(1);

    private final AsyncLoadingCache<Long, List<ProductCoupon>> cache;

    /** @param executor 로드를 실행할 곳. 시간 경계 테스트는 호출 스레드에서 바로 실행하는 값을 넘긴다. */
    public ProductCouponService(ProductCouponClient client, Duration cacheTtl, LongSupplier nanoTime,
            Executor executor) {
        // 갱신이 없던 항목은 MAX_STALE 뒤 동기 조회해, 한동안 안 본 상품이 오래된 쿠폰을 주지 않게 한다.
        // Caffeine 은 로드를 map compute 안에서 제출하므로 실행은 비동기여야 한다. 가상 스레드라 공용 풀과 상한을 공유하지 않는다.
        this.cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .refreshAfterWrite(cacheTtl)
            .expireAfterWrite(MAX_STALE)
            .ticker(nanoTime::getAsLong)
            .executor(executor)
            .buildAsync(productId -> load(client, productId));
    }

    /** 쿠폰을 확인하지 못하면 빈 목록을 돌려준다. 예외를 밖으로 내보내지 않는다. */
    public List<ProductCoupon> findByProductId(long productId) {
        return cache.get(productId).join();
    }

    private static List<ProductCoupon> load(ProductCouponClient client, long productId) {
        try {
            return List.copyOf(client.findByProductId(productId));
        } catch (RuntimeException e) {
            log.warn("product coupon lookup failed: productId={} cause={}", productId, e.toString());
            return List.of();
        }
    }
}
