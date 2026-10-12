package com.shoppinglive.shopping.coupon.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 상품별 쿠폰 목록을 짧게 캐시한다. 캐시는 Pod 마다 따로 있다 (분산 캐시 아님).
 *
 * <p>같은 상품의 동시 미스는 조회를 한 번만 하고 나머지는 그 결과를 기다린다. 실패는 빈 목록으로 캐시해
 * Commerce 장애 중 요청이 업스트림으로 몰리지 않게 한다. 만료된 항목을 갱신하는 동안에는 이전 값을 바로 돌려주고,
 * 이전 값이 없는 첫 조회만 결과를 기다린다(stale-while-refresh): 느린 Commerce 가 요청 스레드를 붙잡지 않게 한다.
 */
public class ProductCouponService {

    // shortcut: 항목 1만 개 초과 시 만료분을 지우고 그래도 차면 전부 비운다(비운 뒤엔 같은 상품을 한 번 더 조회).
    //   상품 수나 메모리가 측정으로 문제가 되면 Caffeine(maximumSize, expireAfterWrite)으로 바꾼다.
    static final int MAX_ENTRIES = 10_000;

    private static final Logger log = LoggerFactory.getLogger(ProductCouponService.class);

    private final ProductCouponClient client;
    private final long cacheTtlNanos;
    private final long failureTtlNanos;
    private final LongSupplier nanoTime;
    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();

    public ProductCouponService(ProductCouponClient client, Duration cacheTtl, Duration failureTtl,
            LongSupplier nanoTime) {
        this.client = client;
        this.cacheTtlNanos = cacheTtl.toNanos();
        this.failureTtlNanos = failureTtl.toNanos();
        this.nanoTime = nanoTime;
    }

    /** 쿠폰을 확인하지 못하면 빈 목록을 돌려준다. 예외를 밖으로 내보내지 않는다. */
    public List<ProductCoupon> findByProductId(final long productId) {
        final long now = nanoTime.getAsLong();
        if (entries.size() >= MAX_ENTRIES) {
            prune(now);
        }
        // 조회 자체는 compute 밖에서 한다. 안에서 원격 호출을 하면 같은 bin 의 다른 상품 요청이 함께 막힌다.
        final Entry entry = entries.compute(productId,
            (id, current) -> current != null && current.isFresh(now) ? current : new Entry(current));
        if (entry.claimLoad()) {
            load(productId, entry);
        } else if (entry.previous != null && !entry.result.isDone()) {
            return entry.previous;
        }
        return entry.result.join();
    }

    private void load(final long productId, final Entry entry) {
        List<ProductCoupon> coupons = List.of();
        long ttl = failureTtlNanos;
        try {
            coupons = List.copyOf(client.findByProductId(productId));
            ttl = cacheTtlNanos;
        } catch (RuntimeException e) {
            log.warn("product coupon lookup failed: productId={} cause={}", productId, e.toString());
        } finally {
            // Error 로 빠져나가도 기다리는 요청이 끝없이 대기하지 않도록 future 를 반드시 완료한다.
            entry.expiresAtNanos = nanoTime.getAsLong() + ttl;
            entry.result.complete(coupons);
        }
    }

    private void prune(final long now) {
        entries.values().removeIf(entry -> entry.isExpired(now));
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
    }

    private static final class Entry {

        private final AtomicBoolean loading = new AtomicBoolean();
        private final CompletableFuture<List<ProductCoupon>> result = new CompletableFuture<>();
        // 교체된 항목의 마지막 완료 값(실패였다면 빈 목록). 첫 조회라 없으면 null.
        private final List<ProductCoupon> previous;
        private volatile long expiresAtNanos;

        /** @param replaced 만료되어 교체되는 항목. 교체가 아니면 null. 교체 대상은 항상 완료된 항목이다. */
        Entry(final Entry replaced) {
            this.previous = replaced == null ? null : replaced.result.join();
        }

        boolean claimLoad() {
            return loading.compareAndSet(false, true);
        }

        // 조회 중인 항목은 유효하다: 같은 상품의 요청은 새로 조회하지 않고 이 결과를 기다린다.
        boolean isFresh(final long now) {
            return !result.isDone() || now - expiresAtNanos < 0;
        }

        boolean isExpired(final long now) {
            return result.isDone() && now - expiresAtNanos >= 0;
        }
    }
}
