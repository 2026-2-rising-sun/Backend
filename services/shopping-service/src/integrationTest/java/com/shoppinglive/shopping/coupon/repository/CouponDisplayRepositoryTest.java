package com.shoppinglive.shopping.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.dto.ProductCouponResponse;
import java.time.Instant;
import java.util.List;
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
    record Fixture(CouponDisplayRepository repository, TransactionTemplate tx) {
        boolean apply(CouponSnapshot snapshot) {
            return Boolean.TRUE.equals(tx.execute(status -> repository.apply(snapshot)));
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
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }
}
