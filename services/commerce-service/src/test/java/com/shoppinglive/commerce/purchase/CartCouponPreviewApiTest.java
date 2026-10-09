package com.shoppinglive.commerce.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.cart.application.CartService;
import com.shoppinglive.commerce.cart.domain.CartItem;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cart_coupon_preview_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(scripts={"/db/migration/V5__coupon_definitions.sql", "/db/migration/V6__member_coupons.sql"},
    executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class CartCouponPreviewApiTest extends CommerceSecurityTestSupport {
    private static final String COUPON_ID = "66666666-6666-4666-8666-666666666666";

    @Autowired MockMvc mvc;
    @Autowired CartService cart;
    @Autowired CartItemRepository items;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired JdbcTemplate jdbc;
    private CartItem first;
    private CartItem second;
    private CartItem ineligible;

    @BeforeEach
    void setUp() {
        clean();
        shopping.register(new ProductSnapshot(1L, "A", null));
        shopping.register(new ProductSnapshot(2L, "B", null));
        shopping.register(new ProductSnapshot(3L, "미대상", null));
        var firstSales = sales.saveAndFlush(new Sales(1L, 10_000L, SalesStatus.ON_SALE));
        var secondSales = sales.saveAndFlush(new Sales(2L, 5_000L, SalesStatus.ON_SALE));
        var thirdSales = sales.saveAndFlush(new Sales(3L, 1_000L, SalesStatus.ON_SALE));
        stocks.saveAndFlush(new SalesStock(firstSales.getId(), 10, 0));
        stocks.saveAndFlush(new SalesStock(secondSales.getId(), 10, 0));
        stocks.saveAndFlush(new SalesStock(thirdSales.getId(), 10, 0));
        first = cart.add(MEMBER_A, 1L, 2);
        second = cart.add(MEMBER_A, 2L, 1);
        ineligible = cart.add(MEMBER_A, 3L, 1);
    }

    @AfterEach
    void tearDown() {
        clean();
        shopping.clear();
    }

    @Test
    void appliesCouponOnlyToEligibleCartOrdersWithoutReservingAnything() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                starts_at,ends_at,expires_at,created_at) VALUES (?,?,?,3001,2,1,?,?,?,?)
            """, COUPON_ID, SELLER, "장바구니 쿠폰", Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.plusSeconds(7200)), Timestamp.from(now));
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON_ID, 1L);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", COUPON_ID, 2L);
        jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',?)",
            "77777777-7777-4777-8777-777777777777", COUPON_ID, MEMBER_A, Timestamp.from(now));

        String body = """
            {"items":[{"itemId":%d,"version":%d},{"itemId":%d,"version":%d},{"itemId":%d,"version":%d}],"couponId":"%s"}
            """.formatted(first.getId(), first.getVersion(), second.getId(), second.getVersion(),
                ineligible.getId(), ineligible.getVersion(), COUPON_ID);
        mvc.perform(post("/v1/cart/checkout").header("Authorization", bearer(MEMBER_A))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAmount").value(26_000))
            .andExpect(jsonPath("$.couponId").value(COUPON_ID))
            .andExpect(jsonPath("$.discountAmount").value(3_001))
            .andExpect(jsonPath("$.payableAmount").value(22_999))
            .andExpect(jsonPath("$.items[0].discountAmount").value(2_401))
            .andExpect(jsonPath("$.items[1].discountAmount").value(600))
            .andExpect(jsonPath("$.items[2].discountAmount").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM member_coupon WHERE coupon_id=?", String.class, COUPON_ID))
            .isEqualTo("AVAILABLE");
        assertThat(items.count()).isEqualTo(3);
        assertThat(stocks.findAll()).extracting(SalesStock::getAvailable).containsOnly(10);
        assertThat(stocks.findAll()).extracting(SalesStock::getReserved).containsOnly(0);
    }

    private void clean() {
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", COUPON_ID);
        items.deleteAll();
        stocks.deleteAll();
        sales.deleteAll();
    }
}
