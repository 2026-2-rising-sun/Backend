package com.shoppinglive.shopping.coupon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductCouponServiceTest {

    private static final long PRODUCT_ID = 7L;
    private static final Duration TTL = Duration.ofSeconds(3);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final ProductCouponClient client = mock(ProductCouponClient.class);
    private final ProductCouponService service = new ProductCouponService(client, TTL, nanos::get);

    @Test
    @DisplayName("캐시된 쿠폰은 TTL 이 지나기 전까지 Commerce 를 다시 부르지 않고, TTL 이 지나면 이전 값을 주면서 다시 조회한다")
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

        // when: TTL 을 넘기면 갱신 조회가 백그라운드에서 한 번 일어난다
        nanos.addAndGet(Duration.ofMillis(2).toNanos());
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(coupons);

        // then
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(client, times(2)).findByProductId(PRODUCT_ID));
    }

    @Test
    @DisplayName("같은 상품에 대한 동시 캐시 미스는 Commerce 조회를 한 번만 하고 모든 요청이 그 결과를 받는다")
    void concurrentMissesForOneProductCallUpstreamOnce() throws Exception {
        // given: 조회가 시작된 뒤에도 응답을 붙잡아 두어, 나머지 요청이 같은 조회를 기다리는 상황을 만든다
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<ProductCoupon> coupons = List.of(coupon("c1"));
        when(client.findByProductId(PRODUCT_ID)).thenAnswer(invocation -> {
            loaderEntered.countDown();
            release.await();
            return coupons;
        });
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<ProductCoupon>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> service.findByProductId(PRODUCT_ID)));
            }

            // when
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            // then
            for (Future<List<ProductCoupon>> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(coupons);
            }
            verify(client, times(1)).findByProductId(PRODUCT_ID);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Commerce 조회 실패는 빈 목록으로 캐시되어 TTL 동안 다시 부르지 않고, TTL 이 지나면 복구된 쿠폰을 보여준다")
    void upstreamFailureIsCachedAsEmptyAndRecoversAfterCacheTtl() {
        // given
        List<ProductCoupon> recovered = List.of(coupon("c1"));
        when(client.findByProductId(PRODUCT_ID))
            .thenThrow(new ProductCouponUnavailableException("commerce down", new IllegalStateException("timeout")))
            .thenReturn(recovered);

        // when / then: 실패는 빈 목록으로 돌려주고 TTL 동안 재호출하지 않는다
        assertThat(service.findByProductId(PRODUCT_ID)).isEmpty();
        nanos.addAndGet(Duration.ofMillis(2_999).toNanos());
        assertThat(service.findByProductId(PRODUCT_ID)).isEmpty();
        verify(client, times(1)).findByProductId(PRODUCT_ID);

        // when / then: TTL 이 지나면 갱신 조회가 복구된 값을 가져온다
        nanos.addAndGet(Duration.ofMillis(2).toNanos());
        service.findByProductId(PRODUCT_ID);
        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(recovered));
        verify(client, times(2)).findByProductId(PRODUCT_ID);
    }

    @Test
    @DisplayName("상품마다 캐시 항목이 따로 있어 한 상품의 쿠폰이 다른 상품 조회에 섞이지 않는다")
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

    @Test
    @DisplayName("TTL 이 지난 캐시의 갱신 조회가 도는 동안 모든 요청은 이전 값을 바로 받고, 갱신 조회는 한 번만 일어난다")
    void staleValueIsServedWhileOneRefreshRuns() throws Exception {
        // given: 이전 값이 캐시되어 있고, 갱신 조회는 응답 전까지 막혀 있다
        List<ProductCoupon> previous = List.of(coupon("old"));
        List<ProductCoupon> refreshed = List.of(coupon("new"));
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.findByProductId(PRODUCT_ID)).thenReturn(previous).thenAnswer(invocation -> {
            loaderEntered.countDown();
            release.await();
            return refreshed;
        });
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(previous);
        nanos.addAndGet(Duration.ofSeconds(3).plusMillis(1).toNanos());
        try {
            // when / then: 갱신 조회가 시작되어 멈춰 있어도 요청은 기다리지 않고 이전 값을 받는다
            assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(previous);
            assertThat(loaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(previous);
            verify(client, times(2)).findByProductId(PRODUCT_ID);

            // when: 갱신이 끝나면 새 값이 보인다
            release.countDown();
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(refreshed));
            verify(client, times(2)).findByProductId(PRODUCT_ID);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("오래 조회되지 않은 상품은 1분이 지난 뒤 첫 요청에서 이전 값 대신 새 값을 받는다")
    void entryUnusedBeyondMaxStaleIsReloadedBeforeServing() {
        // given
        List<ProductCoupon> previous = List.of(coupon("old"));
        List<ProductCoupon> latest = List.of(coupon("new"));
        when(client.findByProductId(PRODUCT_ID)).thenReturn(previous).thenReturn(latest);
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(previous);

        // when
        nanos.addAndGet(Duration.ofMinutes(1).plusMillis(1).toNanos());

        // then
        assertThat(service.findByProductId(PRODUCT_ID)).isEqualTo(latest);
        verify(client, times(2)).findByProductId(PRODUCT_ID);
    }

    private static ProductCoupon coupon(String id) {
        return new ProductCoupon(id, "가을 니트 5천원 할인", 5_000L, false,
            Instant.parse("2026-10-15T11:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"));
    }
}
