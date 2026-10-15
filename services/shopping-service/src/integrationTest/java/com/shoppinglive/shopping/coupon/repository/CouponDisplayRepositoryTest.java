package com.shoppinglive.shopping.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.dto.ProductCouponResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CouponDisplayRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");
    private static final Instant START = T0.plusSeconds(3600);
    private static final Instant END = T0.plusSeconds(7200);
    private static final Instant EXPIRE = T0.plusSeconds(86400);
    private static final Instant DURING = START.plusSeconds(60);

    record Db(String label, String url, String user, String password) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** 운영에서는 Spring 이 {@code apply} 를 transaction 으로 감싼다. 테스트에서는 같은 효과를 직접 만든다. */
    record Fixture(
            CouponDisplayRepository repository,
            TransactionTemplate tx,
            NamedParameterJdbcTemplate jdbc) {
        boolean apply(CouponSnapshot snapshot) {
            return Boolean.TRUE.equals(tx.execute(status -> repository.apply(snapshot)));
        }

        /** 저장소가 처음 실행하는 쓰기 SQL 바로 뒤에 {@code hook} 을 한 번 실행하는 Fixture. 경합 순서를 고정할 때 쓴다. */
        Fixture withHookAfterFirstStatement(Runnable hook) {
            AtomicBoolean fired = new AtomicBoolean();
            NamedParameterJdbcTemplate hooked =
                    new NamedParameterJdbcTemplate(jdbc.getJdbcTemplate()) {
                        @Override
                        public int update(String sql, SqlParameterSource params) {
                            int rows = super.update(sql, params);
                            if (fired.compareAndSet(false, true)) {
                                hook.run();
                            }
                            return rows;
                        }
                    };
            return new Fixture(new CouponDisplayRepository(hooked), tx, hooked);
        }
    }

    static Stream<Db> databases() {
        Db h2 =
                new Db(
                        "h2",
                        "jdbc:h2:mem:coupon_display_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        String postgres = System.getenv("SHOPPING_TEST_POSTGRES_URL");
        if (postgres == null || postgres.isBlank()) {
            return Stream.of(h2);
        }
        return Stream.of(
                h2,
                new Db(
                        "postgres",
                        postgres,
                        System.getenv().getOrDefault("SHOPPING_TEST_POSTGRES_USER", "postgres"),
                        System.getenv()
                                .getOrDefault("SHOPPING_TEST_POSTGRES_PASSWORD", "postgres")));
    }

    @ParameterizedTest
    @DisplayName("처음 받은 쿠폰은 대상 상품마다 조회된다")
    @MethodSource("databases")
    void newCouponIsVisibleForEachTargetProduct(Db db) {
        Fixture f = open(db);

        assertThat(f.apply(coupon("c1", 1, "가을 쿠폰", List.of(501L, 502L), false))).isTrue();

        assertThat(names(f, 501L, DURING)).containsExactly("가을 쿠폰");
        assertThat(names(f, 502L, DURING)).containsExactly("가을 쿠폰");
        assertThat(names(f, 503L, DURING)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("더 큰 version 은 쿠폰 값과 대상 상품을 통째로 바꾸고, 빠진 상품의 연결을 지운다")
    @MethodSource("databases")
    void largerVersionReplacesValuesAndTargets(Db db) {
        Fixture f = open(db);
        f.apply(coupon("c1", 1, "옛 이름", List.of(501L, 502L), false));

        assertThat(f.apply(coupon("c1", 2, "새 이름", List.of(502L, 503L), true))).isTrue();

        assertThat(names(f, 501L, DURING)).isEmpty();
        assertThat(names(f, 502L, DURING)).containsExactly("새 이름");
        assertThat(f.repository().findActiveByProductId(503L, DURING).getFirst().soldOut())
                .isTrue();
    }

    @ParameterizedTest
    @DisplayName("같거나 작은 version 은 버린다 (중복·역순 이벤트)")
    @MethodSource("databases")
    void sameOrSmallerVersionIsIgnored(Db db) {
        Fixture f = open(db);
        f.apply(coupon("c1", 2, "최신", List.of(501L), false));

        assertThat(f.apply(coupon("c1", 2, "중복", List.of(502L), false))).isFalse();
        assertThat(f.apply(coupon("c1", 1, "옛것", List.of(502L), false))).isFalse();

        assertThat(names(f, 501L, DURING)).containsExactly("최신");
        assertThat(names(f, 502L, DURING)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("대상 상품이 빈 목록이면 모든 연결을 지운다")
    @MethodSource("databases")
    void emptyTargetsRemoveAllLinks(Db db) {
        Fixture f = open(db);
        f.apply(coupon("c1", 1, "쿠폰", List.of(501L), false));

        assertThat(f.apply(coupon("c1", 2, "쿠폰", List.of(), false))).isTrue();

        assertThat(names(f, 501L, DURING)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("발급 기간은 시작을 포함하고 종료를 제외한다")
    @MethodSource("databases")
    void periodIncludesStartAndExcludesEnd(Db db) {
        Fixture f = open(db);
        f.apply(coupon("c1", 1, "쿠폰", List.of(501L), false));

        assertThat(names(f, 501L, START.minusMillis(1))).isEmpty();
        assertThat(names(f, 501L, START)).containsExactly("쿠폰");
        assertThat(names(f, 501L, END.minusMillis(1))).containsExactly("쿠폰");
        assertThat(names(f, 501L, END)).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("한 상품의 쿠폰은 종료가 빠른 순서로 나온다")
    @MethodSource("databases")
    void couponsAreOrderedByEnd(Db db) {
        Fixture f = open(db);
        f.apply(coupon("late", 1, "늦게 끝남", List.of(501L), false, END.plusSeconds(60)));
        f.apply(coupon("early", 1, "먼저 끝남", List.of(501L), false, END));

        assertThat(names(f, 501L, DURING)).containsExactly("먼저 끝남", "늦게 끝남");
    }

    @ParameterizedTest
    @DisplayName("진행 중이거나 시작 전인 쿠폰 id 만 대조 대상으로 고른다")
    @MethodSource("databases")
    void openCouponIdsExcludeEnded(Db db) {
        Fixture f = open(db);
        f.apply(coupon("open", 1, "진행", List.of(501L), false));
        f.apply(coupon("ended", 1, "종료", List.of(501L), false, START.minusSeconds(1)));

        assertThat(f.repository().findOpenCouponIds(DURING)).containsExactly("open");
    }

    @ParameterizedTest
    @DisplayName("두 쓰기가 같은 신규 쿠폰을 동시에 저장해도 높은 version 이 남는다")
    @MethodSource("databases")
    void concurrentFirstWritesKeepHigherVersion(Db db) throws Exception {
        Fixture f = open(db);
        // 쓰기 A(version 2)가 첫 SQL 을 실행한 직후, 다른 스레드의 쓰기 B(version 1)를 끼워 넣는다.
        // 예전 순서(UPDATE → INSERT)에서는 B 가 이 틈에 commit 해서 A 가 0건으로 끝나고 version 1 이 남았다.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Boolean>> writerB = new AtomicReference<>();
        try {
            Fixture interleaved =
                    f.withHookAfterFirstStatement(
                            () -> {
                                writerB.set(
                                        executor.submit(
                                                () ->
                                                        applyWithRetryOnKeyConflict(
                                                                f,
                                                                coupon(
                                                                        "c1",
                                                                        1,
                                                                        "옛 이름",
                                                                        List.of(501L),
                                                                        false))));
                                // B 가 A 의 commit 을 기다리며 막힐 수 있다(A 가 먼저 INSERT 한 경우).
                                // 그래서 여기서는 끝까지 기다리지 않고, B 가 끼어들 시간만 준다.
                                waitBriefly(writerB.get());
                            });

            boolean appliedA = interleaved.apply(coupon("c1", 2, "새 이름", List.of(502L), true));
            // A 가 commit 된 뒤에는 B 가 반드시 끝나야 한다. B 의 예외도 여기서 그대로 테스트 실패가 된다.
            boolean appliedB = writerB.get().get(10, TimeUnit.SECONDS);

            assertThat(appliedA).isTrue();
            assertThat(appliedB).as("낮은 version 인 B 는 반영되면 안 된다").isFalse();
            assertThat(names(f, 501L, DURING)).isEmpty();
            assertThat(names(f, 502L, DURING)).containsExactly("새 이름");
            assertThat(f.repository().findActiveByProductId(502L, DURING).getFirst().soldOut())
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 동기화({@code CouponSync})와 같은 방식: 키 충돌이면 transaction 밖에서 한 번 더 부른다. 키 충돌은 다른 쓰기가 같은 신규 쿠폰을 먼저
     * 넣었다는 뜻이라 예상된 실패다. 그 밖의 예외는 잡지 않고 호출한 쪽(테스트)으로 그대로 올린다.
     */
    private static boolean applyWithRetryOnKeyConflict(Fixture f, CouponSnapshot snapshot) {
        try {
            return f.apply(snapshot);
        } catch (DuplicateKeyException e) {
            return f.apply(snapshot);
        }
    }

    /** B 가 끝났으면 바로, 막혀 있으면 잠시 뒤 돌아온다. B 의 예외는 무시하지 않고 나중에 {@code get} 에서 확인한다. */
    private static void waitBriefly(Future<?> writer) {
        try {
            writer.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // 막혀 있거나 실패했다. 어느 쪽이든 테스트 본문의 get(10초)이 결과를 다시 확인한다.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(Fixture f, long productId, Instant now) {
        return f.repository().findActiveByProductId(productId, now).stream()
                .map(ProductCouponResponse::name)
                .toList();
    }

    private static CouponSnapshot coupon(
            String id, long version, String name, List<Long> products, boolean soldOut) {
        return coupon(id, version, name, products, soldOut, END);
    }

    private static CouponSnapshot coupon(
            String id,
            long version,
            String name,
            List<Long> products,
            boolean soldOut,
            Instant endsAt) {
        Instant startsAt = endsAt.isAfter(START) ? START : endsAt.minusSeconds(3600);
        return new CouponSnapshot(
                id, version, name, 1000, products, startsAt, endsAt, EXPIRE, soldOut);
    }

    private static Fixture open(Db db) {
        DataSource dataSource = new DriverManagerDataSource(db.url(), db.user(), db.password());
        Flyway.configure().dataSource(dataSource).load().migrate();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcOperations().update("DELETE FROM coupon_display_product");
        jdbc.getJdbcOperations().update("DELETE FROM coupon_display");
        return new Fixture(
                new CouponDisplayRepository(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                jdbc);
    }
}
