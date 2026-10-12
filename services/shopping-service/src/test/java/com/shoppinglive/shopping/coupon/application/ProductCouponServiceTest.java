package com.shoppinglive.shopping.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProductCouponServiceTest {

    private static final long PRODUCT_ID = 7L;

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final ProductCouponClient client = mock(ProductCouponClient.class);
    private final ProductCouponService service = new ProductCouponService(
        client, Duration.ofSeconds(3), Duration.ofSeconds(1), nanos::get);

    @Test
    void freshEntryIsServedWithoutUpstreamCallUntilCacheTtlExpires() {
        // given
        List<ProductCoupon> coupons = List.of(coupon("c1"));
        when(client.findByProductId(PRODUCT_ID)).thenReturn(coupons);

        // when
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(coupons);
        nanos.addAndGet(Duration.ofMillis(2_999).toNanos());
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(coupons);

        // then
        verify(client, times(1)).findByProductId(PRODUCT_ID);

        // when: TTL 이 지나면 다시 조회한다
        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        service.findByProductId(PRODUCT_ID);

        // then
        verify(client, times(2)).findByProductId(PRODUCT_ID);
    }

    @Test
    void concurrentMissesForOneProductCallUpstreamOnce() throws Exception {
        // given: 업스트림 응답을 짧게 붙잡아 나머지 요청이 같은 조회를 기다리는 상황을 만든다
        CountDownLatch release = new CountDownLatch(1);
        List<ProductCoupon> coupons = List.of(coupon("c1"));
        when(client.findByProductId(PRODUCT_ID)).thenAnswer(invocation -> {
            release.await();
            return coupons;
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<ProductCoupon>>> results = new ArrayList<>();

            // when
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> service.findByProductId(PRODUCT_ID)));
            }
            Thread.sleep(200);
            release.countDown();

            // then
            for (Future<List<ProductCoupon>> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(coupons);
            }
            verify(client, times(1)).findByProductId(PRODUCT_ID);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void upstreamFailureIsCachedAsEmptyUntilFailureTtlThenRecovers() {
        // given
        List<ProductCoupon> recovered = List.of(coupon("c1"));
        when(client.findByProductId(PRODUCT_ID))
            .thenThrow(new ProductCouponUnavailableException("commerce down"))
            .thenReturn(recovered);

        // when / then: 실패도 빈 목록으로 캐시되어 실패 TTL 동안 재호출하지 않는다
        assertThat(service.findByProductId(PRODUCT_ID)).isEmpty();
        nanos.addAndGet(Duration.ofMillis(999).toNanos());
        assertThat(service.findByProductId(PRODUCT_ID)).isEmpty();
        verify(client, times(1)).findByProductId(PRODUCT_ID);

        // when / then: 실패 TTL 이 지나면 다시 조회하고 복구가 보인다
        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(recovered);
        verify(client, times(2)).findByProductId(PRODUCT_ID);
    }

    @Test
    void differentProductsDoNotShareEntries() {
        // given
        when(client.findByProductId(1L)).thenReturn(List.of(coupon("a")));
        when(client.findByProductId(2L)).thenReturn(List.of(coupon("b")));

        // when / then
        assertThat(service.findByProductId(1L)).extracting(ProductCoupon::id).containsExactly("a");
        assertThat(service.findByProductId(2L)).extracting(ProductCoupon::id).containsExactly("b");
        verify(client, times(1)).findByProductId(1L);
        verify(client, times(1)).findByProductId(2L);
    }

    private static ProductCoupon coupon(final String id) {
        return new ProductCoupon(id, "가을 니트 5천원 할인", 5_000L, false,
            Instant.parse("2026-10-15T11:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"));
    }
}
