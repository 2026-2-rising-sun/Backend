package com.shoppinglive.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PartialIndexerTest {
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private FakeIndex index;
    private PartialIndexer indexer;

    @BeforeEach
    void prepare() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:search_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        index = new FakeIndex();
        indexer = indexer(locks(jdbc));
    }

    private PartialIndexer indexer(LockingTaskExecutor locks) {
        return new PartialIndexer(
                named, new ProductSearchSource(named), index, locks, 100, Duration.ofMinutes(1));
    }

    private static LockingTaskExecutor locks(JdbcTemplate jdbc) {
        return new DefaultLockingTaskExecutor(
                new JdbcTemplateLockProvider(
                        JdbcTemplateLockProvider.Configuration.builder()
                                .withJdbcTemplate(jdbc)
                                .usingDbTime()
                                .build()));
    }

    private void product(long id, String name) {
        jdbc.update(
                "INSERT INTO product(id,name,description,main_image_id,main_image_url,category,version,created_at,updated_at)"
                        + " VALUES(?,?,'설명',1,'/img',?,0,?,?)",
                id,
                name,
                "FASHION",
                java.sql.Timestamp.from(Instant.parse("2026-11-24T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-11-24T00:00:00Z")));
    }

    private void sku(long skuId, long productId, long price, String status) {
        jdbc.update(
                "INSERT INTO product_sales(sales_id,product_id,price,status,available,version,updated_at)"
                        + " VALUES(?,?,?,?,?,0,CURRENT_TIMESTAMP)",
                skuId,
                productId,
                price,
                status,
                status.equals("SOLD_OUT") ? 0 : 5);
    }

    private void task(long productId) {
        jdbc.update("INSERT INTO index_task(product_id) VALUES(?)", productId);
    }

    private long tasks() {
        return jdbc.queryForObject("SELECT count(*) FROM index_task", Long.class);
    }

    @Test
    @DisplayName("판매 중 SKU 최저가를 대표 가격으로 색인하고 처리한 작업을 지운다")
    void indexesRepresentativePriceAndClearsTasks() {
        product(1, "기본 티셔츠");
        sku(11, 1, 17000, "ON_SALE");
        sku(12, 1, 15000, "ON_SALE");
        sku(13, 1, 9000, "SOLD_OUT");
        task(1);
        task(1);

        assertThat(indexer.indexOnce()).isEqualTo(1);

        ProductSearchDocument document = index.documents.get(1L);
        assertThat(document.price()).isEqualTo(15000);
        assertThat(document.soldOut()).isFalse();
        assertThat(document.status()).isEqualTo("ON_SALE");
        assertThat(tasks()).isZero();
    }

    @Test
    @DisplayName("모든 SKU가 품절이면 품절 최저가로 품절 문서를 만든다")
    void indexesSoldOutProduct() {
        product(2, "품절 상품");
        sku(21, 2, 8000, "SOLD_OUT");
        sku(22, 2, 7000, "SOLD_OUT");
        task(2);

        indexer.indexOnce();

        assertThat(index.documents.get(2L).price()).isEqualTo(7000);
        assertThat(index.documents.get(2L).status()).isEqualTo("SOLD_OUT");
    }

    @Test
    @DisplayName("삭제되거나 공개 SKU가 없는 상품은 문서를 지운다")
    void deletesHiddenProducts() {
        product(3, "삭제 상품");
        sku(31, 3, 1000, "ON_SALE");
        jdbc.update(
                "UPDATE product SET sale_enabled=FALSE, deleted_at=CURRENT_TIMESTAMP WHERE id=3");
        product(4, "비공개 상품");
        sku(41, 4, 1000, "PRIVATE");
        task(3);
        task(4);
        task(5);

        assertThat(indexer.indexOnce()).isEqualTo(3);

        assertThat(index.deleted).containsExactlyInAnyOrder(3L, 4L, 5L);
        assertThat(tasks()).isZero();
    }

    @Test
    @DisplayName("ES가 반영하지 못한 상품과 ES 장애 때의 작업은 남겨 다음 주기에 처리한다")
    void keepsTasksOnFailure() {
        product(6, "실패 상품");
        sku(61, 6, 1000, "ON_SALE");
        product(7, "성공 상품");
        sku(71, 7, 1000, "ON_SALE");
        task(6);
        task(7);
        index.failing.add(6L);

        assertThat(indexer.indexOnce()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT product_id FROM index_task", Long.class))
                .containsExactly(6L);

        index.down = true;
        assertThat(indexer.indexOnce()).isZero();
        assertThat(tasks()).isEqualTo(1);

        index.down = false;
        index.failing.clear();
        assertThat(indexer.indexOnce()).isEqualTo(1);
        assertThat(tasks()).isZero();
    }

    @Test
    @DisplayName("색인하는 동안 같은 상품이 다시 바뀌면 새 작업은 지우지 않는다")
    void keepsTaskAddedDuringIndexing() {
        product(8, "상품");
        sku(81, 8, 1000, "ON_SALE");
        task(8);
        index.beforeWrite = () -> task(8);

        indexer.indexOnce();

        assertThat(tasks()).isEqualTo(1);
    }

    @Test
    @DisplayName("Pod 두 개가 같은 잠금으로 동시에 실행하면 색인은 한 번만 돈다")
    void runsOnlyOnceAcrossInstances() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        LockingTaskExecutor first = locks(jdbc);
        LockingTaskExecutor second = locks(jdbc);
        var config =
                new LockConfiguration(
                        Instant.now(),
                        PartialIndexer.LOCK_NAME,
                        Duration.ofMinutes(1),
                        Duration.ZERO);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holding =
                    executor.submit(
                            () ->
                                    first.executeWithLock(
                                            (Runnable)
                                                    () -> {
                                                        runs.incrementAndGet();
                                                        started.countDown();
                                                        try {
                                                            release.await(5, TimeUnit.SECONDS);
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                        }
                                                    },
                                            config));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            second.executeWithLock((Runnable) runs::incrementAndGet, config);
            release.countDown();
            holding.get(5, TimeUnit.SECONDS);
        }
        assertThat(runs).hasValue(1);
    }

    private static class FakeIndex implements ProductSearchIndex {
        final Map<Long, ProductSearchDocument> documents = new HashMap<>();
        final List<Long> deleted = new ArrayList<>();
        final Set<Long> failing = new HashSet<>();
        boolean down;
        Runnable beforeWrite = () -> {};

        @Override
        public Set<Long> write(
                Collection<ProductSearchDocument> upserts, Collection<Long> deletes) {
            beforeWrite.run();
            if (down) throw new IllegalStateException("ES 연결 실패");
            for (ProductSearchDocument document : upserts)
                if (!failing.contains(document.productId()))
                    documents.put(document.productId(), document);
            for (long id : deletes) if (!failing.contains(id)) deleted.add(id);
            Set<Long> failed = new HashSet<>(failing);
            failed.retainAll(
                    new HashSet<>(upserts.stream().map(ProductSearchDocument::productId).toList()));
            for (long id : deletes) if (failing.contains(id)) failed.add(id);
            return failed;
        }
    }
}
