package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class ProductSalesRepositoryTest {

    private static final long PRODUCT = 1L;

    record Db(String label, String url, String user, String password) {
        @Override
        public String toString() {
            return label;
        }
    }

    record Fixture(ProductSalesRepository repository, TransactionTemplate outer) {
    }

    static Stream<Db> databases() {
        Db h2 = new Db("h2", "jdbc:h2:mem:product_sales_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        String postgres = System.getenv("SHOPPING_TEST_POSTGRES_URL");
        if (postgres == null || postgres.isBlank()) {
            return Stream.of(h2);
        }
        return Stream.of(h2, new Db("postgres", postgres,
            System.getenv().getOrDefault("SHOPPING_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("SHOPPING_TEST_POSTGRES_PASSWORD", "postgres")));
    }

    @ParameterizedTest
    @DisplayName("사본에 없는 상품의 첫 판매정보는 저장되어 그대로 조회된다")
    @MethodSource("databases")
    void newProductIsStored(Db db) {
        Fixture fixture = open(db);

        assertThat(fixture.repository().apply(sales(PRODUCT, 100), 1)).isTrue();
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 100));
    }

    @ParameterizedTest
    @DisplayName("더 큰 version 의 이벤트가 오면 저장된 판매정보를 덮어쓴다")
    @MethodSource("databases")
    void largerVersionOverwrites(Db db) {
        Fixture fixture = open(db);
        fixture.repository().apply(sales(PRODUCT, 100), 1);

        assertThat(fixture.repository().apply(sales(PRODUCT, 200), 2)).isTrue();
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 200));
    }

    @ParameterizedTest
    @DisplayName("같은 version 의 이벤트가 다시 오면 저장된 값이 바뀌지 않는다")
    @MethodSource("databases")
    void sameVersionIsIgnored(Db db) {
        Fixture fixture = open(db);
        fixture.repository().apply(sales(PRODUCT, 100), 1);

        assertThat(fixture.repository().apply(sales(PRODUCT, 200), 1)).isFalse();
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 100));
    }

    @ParameterizedTest
    @DisplayName("더 작은 version 의 이벤트는 저장된 판매정보를 바꾸지 않는다")
    @MethodSource("databases")
    void olderVersionIsIgnored(Db db) {
        Fixture fixture = open(db);
        fixture.repository().apply(sales(PRODUCT, 100), 2);

        assertThat(fixture.repository().apply(sales(PRODUCT, 200), 1)).isFalse();
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 100));
    }

    @ParameterizedTest
    @DisplayName("이벤트로 저장된 행이 있으면 backfill 적재는 그 값을 덮어쓰지 않는다")
    @MethodSource("databases")
    void backfillDoesNotOverwriteEventRow(Db db) {
        Fixture fixture = open(db);
        fixture.repository().apply(sales(PRODUCT, 100), 1);

        assertThat(fixture.repository().insertIfAbsent(sales(PRODUCT, 50))).isFalse();
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 100));
    }

    @ParameterizedTest
    @DisplayName("backfill 로 먼저 들어간 행은 이벤트가 오면 이벤트 값으로 바뀐다")
    @MethodSource("databases")
    void eventOverwritesBackfillRow(Db db) {
        Fixture fixture = open(db);
        assertThat(fixture.repository().insertIfAbsent(sales(PRODUCT, 50))).isTrue();

        assertThat(fixture.repository().apply(sales(PRODUCT, 100), 1)).isTrue();
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 100));
    }

    @ParameterizedTest
    @DisplayName("조회 대상 중 사본에 행이 있는 상품만 반환하고, 빈 목록 조회는 빈 결과를 돌려준다")
    @MethodSource("databases")
    void findReturnsOnlyExistingRows(Db db) {
        Fixture fixture = open(db);
        fixture.repository().apply(sales(1L, 100), 1);
        fixture.repository().apply(sales(2L, 200), 1);

        assertThat(fixture.repository().findByProductIds(List.of(1L, 2L, 3L)))
            .isEqualTo(Map.of(1L, sales(1L, 100), 2L, sales(2L, 200)));
        assertThat(fixture.repository().findByProductIds(List.of())).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("같은 상품의 첫 저장이 여러 스레드에서 동시에 들어와도 가장 높은 version 의 값이 남는다")
    @MethodSource("databases")
    void concurrentFirstInsertKeepsHighestVersion(Db db) throws Exception {
        Fixture fixture = open(db);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long version = 1; version <= threads; version++) {
                long v = version;
                results.add(pool.submit(() -> {
                    start.await();
                    return fixture.repository().apply(sales(PRODUCT, v * 100), v);
                }));
            }
            start.countDown();
            for (Future<Boolean> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, threads * 100L));
    }

    @ParameterizedTest
    @DisplayName("바깥 트랜잭션이 롤백 전용으로 표시되어도 apply 로 저장한 판매정보는 남는다")
    @MethodSource("databases")
    void applyInsideOuterTransactionCommitsIndependently(Db db) {
        Fixture fixture = open(db);

        fixture.outer().executeWithoutResult(status -> {
            fixture.repository().apply(sales(PRODUCT, 100), 1);
            status.setRollbackOnly();
        });

        assertThat(stored(fixture)).containsEntry(PRODUCT, sales(PRODUCT, 100));
    }

    private static Fixture open(Db db) {
        DataSource dataSource = new DriverManagerDataSource(db.url(), db.user(), db.password());
        Flyway.configure().dataSource(dataSource).load().migrate();
        new NamedParameterJdbcTemplate(dataSource).getJdbcOperations().update("DELETE FROM product_sales");
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        return new Fixture(
            new ProductSalesRepository(new NamedParameterJdbcTemplate(dataSource), transactions),
            new TransactionTemplate(transactions));
    }

    private static Map<Long, SalesInfo> stored(Fixture fixture) {
        return fixture.repository().findByProductIds(List.of(PRODUCT));
    }

    private static SalesInfo sales(long productId, long price) {
        return new SalesInfo(productId, 10L, price, SalesStatus.ON_SALE, 5);
    }
}
