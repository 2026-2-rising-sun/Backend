package com.shoppinglive.shopping.coupon.application;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품별 쿠폰 목록을 짧게 캐시한다. 캐시는 Pod 마다 따로 있다 (분산 캐시 아님).
 *
 * <p>같은 상품의 동시 미스는 조회를 한 번만 하고 나머지는 그 결과를 기다린다. 실패는 빈 목록으로 캐시해
 * Commerce 장애 중 요청이 업스트림으로 몰리지 않게 한다. TTL 이 지난 항목은 갱신 조회를 백그라운드에서 한 번 시작하고,
 * 그 동안 모든 요청은 이전 값을 바로 받는다(stale-while-refresh). 같은 상품의 단일 조회는 동시 첫 조회와 갱신에서 보장되지만,
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
