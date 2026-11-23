package com.shoppinglive.commercecore.sku.service;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.exception.InsufficientStockException;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SkuSalesPostgresTest extends SkuSalesIntegrationTest {
    static final String SCHEMA = "sku_sales_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry p) {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.datasource.username", () -> System.getenv("COMMERCECORE_TEST_POSTGRES_USER"));
        p.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.enabled", () -> true);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA);
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 6);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                System.getenv("COMMERCECORE_TEST_POSTGRES_URL"),
                                System.getenv("COMMERCECORE_TEST_POSTGRES_USER"),
                                System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
                var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    @DisplayName("PG 경합에서도 두 보충 중 하나만 남은 공간을 차지하며 예약은 전량 해제된다")
    void concurrentReplenishmentPreservesReleaseHeadroom() throws Exception {
        var sku = register(10);
        sales.changeStatus(sku.skuId(), SalesStatus.ON_SALE, "seller");
        tx.executeWithoutResult(t -> skus.reserve(sku.skuId(), 2));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Callable<Boolean> replenish =
                    () -> {
                        start.await();
                        try {
                            sales.adjustAvailable(sku.skuId(), Integer.MAX_VALUE - 10, "seller");
                            return true;
                        } catch (InsufficientStockException rejected) {
                            return false;
                        }
                    };
            var first = executor.submit(replenish);
            var second = executor.submit(replenish);
            var release =
                    executor.submit(
                            () -> {
                                start.await();
                                tx.executeWithoutResult(t -> skus.release(sku.skuId(), 2));
                                return true;
                            });
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(release.get(30, TimeUnit.SECONDS)).isTrue();
        }
        var current = skus.findBySkuIds(List.of(sku.skuId())).getFirst();
        assertThat(current.available()).isEqualTo(Integer.MAX_VALUE);
        assertThat(current.reserved()).isZero();
    }

    @Test
    @DisplayName("PostgreSQL의 Product 공유 잠금과 SKU 정렬 잠금은 재고5의 동시10요청 중5개만 선점한다")
    void concurrentOrdersCannotOversell() throws Exception {
        var sku = register(5);
        sales.changeStatus(sku.skuId(), SalesStatus.ON_SALE, "seller");
        lifecycle.changeSaleState(product.getId(), true, 0L, "seller");
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(10)) {
            for (int i = 0; i < 10; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        tx.executeWithoutResult(
                                                t -> {
                                                    trading.lockForNewOrder(
                                                            List.of(product.getId()));
                                                    skus.lockForOrder(List.of(sku.skuId()));
                                                    skus.reserve(sku.skuId(), 1);
                                                });
                                        return true;
                                    } catch (InsufficientStockException e) {
                                        return false;
                                    }
                                }));
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(5);
        }
        var current = skus.findBySkuIds(List.of(sku.skuId())).getFirst();
        assertThat(current.available()).isZero();
        assertThat(current.reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("가격 변경과 주문 예약이 경합해도 가격·재고와 이벤트 버전을 모두 보존한다")
    void concurrentPriceChangeAndReservationPreserveBothChanges() throws Exception {
        var sku = register(5);
        sales.changeStatus(sku.skuId(), SalesStatus.ON_SALE, "seller");
        lifecycle.changeSaleState(product.getId(), true, 0L, "seller");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var changePrice =
                    executor.submit(
                            () -> {
                                start.await();
                                return sales.changePrice(sku.skuId(), 2000, "seller");
                            });
            var reserve =
                    executor.submit(
                            () -> {
                                start.await();
                                return tx.execute(
                                        t -> {
                                            trading.lockForNewOrder(List.of(product.getId()));
                                            var locked =
                                                    skus.lockForOrder(List.of(sku.skuId()))
                                                            .getFirst();
                                            skus.reserve(sku.skuId(), 2);
                                            return locked.price();
                                        });
                            });
            start.countDown();
            assertThat(changePrice.get(30, TimeUnit.SECONDS).price()).isEqualTo(2000);
            assertThat(reserve.get(30, TimeUnit.SECONDS)).isIn(1000L, 2000L);
        }
        var current = skus.findBySkuIds(List.of(sku.skuId())).getFirst();
        assertThat(current.price()).isEqualTo(2000);
        assertThat(current.available()).isEqualTo(3);
        assertThat(current.reserved()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT snapshot_version FROM sku WHERE id=?",
                                Long.class,
                                sku.skuId()))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_outbox WHERE topic=? AND message_key=?",
                                Long.class,
                                com.shoppinglive.commercecore.sales.event.SalesInfoEventContract
                                        .TOPIC_NAME,
                                sku.skuId().toString()))
                .isEqualTo(3);
    }
}
