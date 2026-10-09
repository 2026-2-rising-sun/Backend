package com.shoppinglive.commerce.coupons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.coupons.application.CouponPreviewService;
import com.shoppinglive.common.core.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="COMMERCE_TEST_POSTGRES_URL",matches=".+")
class CouponPreviewPostgresTest {
    private static final String COUPON = "coupon-preview";
    private static final String MEMBER = "member";

    @Test
    void validatesOwnedActiveCouponAndTargetAgainstPostgresWithoutReservingIt() {
        String schema = "coupon_preview_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .cleanDisabled(false).load();
        try {
            flyway.migrate();
            Instant now = Instant.parse("2026-10-09T12:00:00Z");
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("""
                INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                    starts_at,ends_at,expires_at,created_at) VALUES (?,?,?,100,2,1,?,?,?,?)
                """, COUPON, "seller", "쿠폰", Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.plusSeconds(7200)), Timestamp.from(now));
            jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 1L);
            jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',?)",
                UUID.randomUUID().toString(), COUPON, MEMBER, Timestamp.from(now));
            var service = new CouponPreviewService(jdbc, Clock.fixed(now, ZoneOffset.UTC));

            var quote = service.preview(MEMBER, COUPON, List.of(
                new CouponPreviewService.PricedItem("cart:1", 1, 1000),
                new CouponPreviewService.PricedItem("cart:2", 2, 2000)));
            assertThat(quote.discountAmount()).isEqualTo(100);
            assertThat(quote.payableAmount()).isEqualTo(2900);
            assertThat(quote.itemDiscounts()).containsEntry("cart:1", 100L).containsEntry("cart:2", 0L);
            assertThatThrownBy(() -> service.preview("other-member", COUPON,
                List.of(new CouponPreviewService.PricedItem("cart:1", 1, 1000))))
                .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.preview(MEMBER, COUPON,
                List.of(new CouponPreviewService.PricedItem("cart:1", 2, 1000))))
                .isInstanceOf(BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM member_coupon WHERE coupon_id=?", String.class, COUPON))
                .isEqualTo("AVAILABLE");
        } finally {
            flyway.clean();
        }
    }
}
