package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.commercecore.product.dto.RegisterProductCommand;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.common.core.BusinessException;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("격리 PostgreSQL 상품 원본·Outbox 원자성·동시 등록")
class ProductPersistencePostgresTest extends ProductPersistenceTest {
    @org.springframework.beans.factory.annotation.Autowired
    jakarta.persistence.EntityManager entityManager;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) throws Exception {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        String user = System.getenv("COMMERCECORE_TEST_POSTGRES_USER");
        String password = System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD");
        String schema = "product_persistence_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        properties.add("spring.datasource.url", () -> url + "?currentSchema=" + schema);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        // 공유 잠금 두 개와 제어 요청 외에 DB 대기 상태를 관측할 연결이 하나 더 필요하다.
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }

    @Test
    @DisplayName("복수 주문의 Product 공유 잠금은 공존하고 전체 중지·삭제는 두 주문 트랜잭션 뒤에 완료된다")
    void sharedOrderLocksCoexistAndSerializeControl() throws Exception {
        for (boolean delete : new boolean[] {false, true}) {
            Product p = enabledProduct();
            var locked = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var writerStarted = new CountDownLatch(1);
            var writerPid = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(3)) {
                Callable<Void> order =
                        () ->
                                transactions.execute(
                                        status -> {
                                            trading.lockForNewOrder(List.of(p.getId()));
                                            locked.countDown();
                                            try {
                                                if (!release.await(20, TimeUnit.SECONDS))
                                                    throw new AssertionError("공유 잠금 해제 신호 누락");
                                            } catch (InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                                throw new AssertionError(interrupted);
                                            }
                                            return null;
                                        });
                var first = executor.submit(order);
                var second = executor.submit(order);
                try {
                    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                    var writer =
                            executor.submit(
                                    () ->
                                            transactions.execute(
                                                    status -> {
                                                        writerPid.set(
                                                                ((Number)
                                                                                entityManager
                                                                                        .createNativeQuery(
                                                                                                "SELECT pg_backend_pid()")
                                                                                        .getSingleResult())
                                                                        .intValue());
                                                        writerStarted.countDown();
                                                        if (delete)
                                                            lifecycle.delete(
                                                                    p.getId(), 1L, "seller");
                                                        else
                                                            lifecycle.changeSaleState(
                                                                    p.getId(), false, 1L, "seller");
                                                        return null;
                                                    }));
                    assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
                    awaitDatabaseLock(writerPid.get(), writer);
                    assertThat(writer.isDone()).isFalse();
                    release.countDown();
                    first.get(10, TimeUnit.SECONDS);
                    second.get(10, TimeUnit.SECONDS);
                    writer.get(10, TimeUnit.SECONDS);
                    assertThatThrownBy(
                                    () ->
                                            transactions.execute(
                                                    status ->
                                                            trading.lockForNewOrder(
                                                                    List.of(p.getId()))))
                            .isInstanceOf(BusinessException.class);
                } finally {
                    release.countDown();
                }
            }
        }
    }

    private Product enabledProduct() {
        var p =
                registration.register(
                        new RegisterProductCommand(
                                "동시 상품", "설명", images.findAll().getFirst().getId(), null),
                        UUID.randomUUID().toString(),
                        "seller");
        return lifecycle.changeSaleState(p.getId(), true, 0L, "seller");
    }

    private void awaitDatabaseLock(int pid, Future<?> writer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (writer.isDone()) {
                writer.get();
                throw new AssertionError("공유 잠금 해제 전에 상품 제어가 완료되었습니다.");
            }
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM pg_locks WHERE pid=? AND NOT granted",
                            Integer.class,
                            pid)
                    > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("상품 제어가 PostgreSQL 잠금 대기에 진입하지 않았습니다.");
    }
}
