package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.coupons.application.CouponReservationService;
import com.shoppinglive.common.core.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="COMMERCE_TEST_POSTGRES_URL",matches=".+")
class CouponReservationPostgresTest {
    private static final String COUPON = "coupon-reservation";
    private static final String MEMBER = "member";

    private void verify(java.util.function.Consumer<Context> test) {
        String schema = "coupon_reservation_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .cleanDisabled(false).load();
        try {
            flyway.migrate();
            var jdbc = new JdbcTemplate(dataSource);
            Instant now = Instant.now();
            jdbc.update("""
                INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                    starts_at,ends_at,expires_at,created_at) VALUES (?,?,?,100,2,1,?,?,?,?)
                """, COUPON, "seller", "쿠폰", Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.plusSeconds(7200)), Timestamp.from(now));
            jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',?)",
                UUID.randomUUID().toString(), COUPON, MEMBER, Timestamp.from(now));
            var manager = new DataSourceTransactionManager(dataSource);
            test.accept(new Context(jdbc, manager, new CouponReservationService(jdbc)));
        } finally {
            flyway.clean();
        }
    }

    @Test
    void oneConcurrentReservationWinsAndTransitionsAreAtomic() {
        verify(context -> {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var results = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("reservation race did not start");
                    try {
                        return new TransactionTemplate(context.manager()).execute(status -> {
                            context.reservations().reserve(MEMBER, COUPON);
                            return true;
                        });
                    } catch (BusinessException conflict) {
                        return false;
                    }
                })).toList();
                try {
                    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                }
                start.countDown();
                assertThat(results.stream().map(future -> {
                    try { return future.get(10, TimeUnit.SECONDS); }
                    catch (Exception exception) { throw new RuntimeException(exception); }
                }).filter(Boolean.TRUE::equals).count()).isEqualTo(1);
            }

            assertThat(status(context.jdbc())).isEqualTo("RESERVED");
            new TransactionTemplate(context.manager()).executeWithoutResult(status ->
                context.reservations().release(MEMBER, COUPON));
            assertThat(status(context.jdbc())).isEqualTo("AVAILABLE");
            new TransactionTemplate(context.manager()).executeWithoutResult(status ->
                context.reservations().reserve(MEMBER, COUPON));
            new TransactionTemplate(context.manager()).executeWithoutResult(status ->
                context.reservations().confirm(MEMBER, COUPON));
            assertThat(status(context.jdbc())).isEqualTo("USED");
            assertThatThrownBy(() -> new TransactionTemplate(context.manager()).executeWithoutResult(status ->
                context.reservations().reserve(MEMBER, COUPON))).isInstanceOf(BusinessException.class);
        });
    }

    @Test
    void reservationRollsBackWithItsCallerTransaction() {
        verify(context -> {
            new TransactionTemplate(context.manager()).executeWithoutResult(status -> {
                context.reservations().reserve(MEMBER, COUPON);
                status.setRollbackOnly();
            });
            assertThat(status(context.jdbc())).isEqualTo("AVAILABLE");
        });
    }

    private static String status(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT status FROM member_coupon WHERE member_id=? AND coupon_id=?",
            String.class, MEMBER, COUPON);
    }

    private record Context(JdbcTemplate jdbc, DataSourceTransactionManager manager,
            CouponReservationService reservations) { }
}
