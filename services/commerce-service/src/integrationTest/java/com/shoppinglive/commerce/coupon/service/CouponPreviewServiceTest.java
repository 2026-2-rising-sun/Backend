package com.shoppinglive.commerce.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.common.core.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CouponPreviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String COUPON = "33333333-3333-4333-8333-333333333334";
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    private JdbcTemplate jdbc;
    private CouponPreviewService service;

    @BeforeEach
    void setUp() {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:coupon_preview_"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/migration/V5__coupon_definitions.sql"),
                        new ClassPathResource("db/migration/V6__member_coupons.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        service = new CouponPreviewService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        jdbc.update(
                """
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                starts_at,ends_at,expires_at,created_at) VALUES (?,?,?,100,2,1,?,?,?,?)
            """,
                COUPON,
                "seller",
                "쿠폰",
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.plusSeconds(7200)),
                Timestamp.from(NOW));
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 1L);
        jdbc.update(
                "INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',?)",
                UUID.randomUUID().toString(),
                COUPON,
                MEMBER,
                Timestamp.from(NOW.minusSeconds(30)));
    }

    @DisplayName("본인 쿠폰 한 장을 대상 상품에만 적용하고 예약하지 않는다")
    @Test
    void appliesOneOwnedCouponOnlyToTargetItemsAndDoesNotReserveIt() {
        var preview =
                service.preview(
                        MEMBER,
                        COUPON,
                        List.of(
                                new CouponPreviewService.PricedItem("cart-1", 1, 1000),
                                new CouponPreviewService.PricedItem("cart-2", 2, 2000)));

        assertThat(preview.couponId()).isEqualTo(COUPON);
        assertThat(preview.discountAmount()).isEqualTo(100);
        assertThat(preview.payableAmount()).isEqualTo(2900);
        assertThat(preview.itemDiscounts())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("cart-1", 100L, "cart-2", 0L));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE coupon_id=?",
                                String.class,
                                COUPON))
                .isEqualTo("AVAILABLE");
    }

    @DisplayName("타인 쿠폰이나 적용할 상품이 없는 쿠폰을 거절한다")
    @Test
    void rejectsCouponNotOwnedByMemberOrWithNoEligibleProduct() {
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        "other",
                                        COUPON,
                                        List.of(
                                                new CouponPreviewService.PricedItem(
                                                        "cart-1", 1, 1000))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MEMBER,
                                        COUPON,
                                        List.of(
                                                new CouponPreviewService.PricedItem(
                                                        "cart-1", 2, 1000))))
                .isInstanceOf(BusinessException.class);
    }

    @DisplayName("예약·사용·만료된 쿠폰은 적용을 거절한다")
    @Test
    void rejectsReservedUsedAndExpiredCoupons() {
        for (String status : List.of("RESERVED", "USED")) {
            jdbc.update("UPDATE member_coupon SET status=? WHERE coupon_id=?", status, COUPON);
            assertThatThrownBy(
                            () ->
                                    service.preview(
                                            MEMBER,
                                            COUPON,
                                            List.of(
                                                    new CouponPreviewService.PricedItem(
                                                            "cart-1", 1, 1000))))
                    .isInstanceOf(BusinessException.class);
        }
        jdbc.update("UPDATE member_coupon SET status='AVAILABLE' WHERE coupon_id=?", COUPON);
        jdbc.update(
                "UPDATE coupon_definition SET ends_at=?,expires_at=? WHERE id=?",
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                COUPON);
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MEMBER,
                                        COUPON,
                                        List.of(
                                                new CouponPreviewService.PricedItem(
                                                        "cart-1", 1, 1000))))
                .isInstanceOf(BusinessException.class);
    }

    @DisplayName("행사가 종료되어도 수령한 쿠폰은 쿠폰 만료 전까지 사용할 수 있다")
    @Test
    void claimedCouponRemainsUsableAfterEventEndsUntilItsExpiry() {
        jdbc.update(
                "UPDATE coupon_definition SET ends_at=?,expires_at=? WHERE id=?",
                Timestamp.from(NOW.minusSeconds(1)),
                Timestamp.from(NOW.plusSeconds(1)),
                COUPON);

        var preview =
                service.preview(
                        MEMBER,
                        COUPON,
                        List.of(new CouponPreviewService.PricedItem("cart-1", 1, 1000)));

        assertThat(preview.discountAmount()).isEqualTo(100);
        assertThat(preview.payableAmount()).isEqualTo(900);
    }

    @DisplayName("쿠폰 행사 시작 시각부터 사용을 허용한다")
    @Test
    void eventStartIsInclusive() {
        jdbc.update(
                "UPDATE coupon_definition SET starts_at=? WHERE id=?", Timestamp.from(NOW), COUPON);
        assertThat(
                        service.preview(
                                        MEMBER,
                                        COUPON,
                                        List.of(
                                                new CouponPreviewService.PricedItem(
                                                        "cart-1", 1, 1000)))
                                .discountAmount())
                .isEqualTo(100);
    }

    @DisplayName("할인 나머지가 같으면 고정된 장바구니 항목 ID 순서로 배분한다")
    @Test
    void allocatesEqualRemainderByStableCartItemId() {
        jdbc.update("UPDATE coupon_definition SET fixed_discount=2 WHERE id=?", COUPON);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 2L);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 3L);

        var preview =
                service.preview(
                        MEMBER,
                        COUPON,
                        List.of(
                                new CouponPreviewService.PricedItem("cart:309", 3, 1000),
                                new CouponPreviewService.PricedItem("cart:205", 2, 1000),
                                new CouponPreviewService.PricedItem("cart:101", 1, 1000)));

        assertThat(preview.discountAmount()).isEqualTo(2);
        assertThat(preview.payableAmount()).isEqualTo(2998);
        assertThat(preview.itemDiscounts())
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of("cart:101", 1L, "cart:205", 1L, "cart:309", 0L));
    }

    @DisplayName("할인 나머지가 같으면 장바구니 항목 ID를 숫자로 비교한다")
    @Test
    void comparesCartItemIdsNumericallyWhenRemaindersTie() {
        jdbc.update("UPDATE coupon_definition SET fixed_discount=1 WHERE id=?", COUPON);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 2L);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON, 3L);

        var preview =
                service.preview(
                        MEMBER,
                        COUPON,
                        List.of(
                                new CouponPreviewService.PricedItem("cart:10", 2, 1000),
                                new CouponPreviewService.PricedItem("cart:2", 3, 1000)));

        assertThat(preview.itemDiscounts())
                .containsEntry("cart:2", 1L)
                .containsEntry("cart:10", 0L);
    }
}
