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
}
