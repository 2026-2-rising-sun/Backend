package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.cart.service.CartService;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.order.service.PaymentGroupService;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 옛 Payment 서버 없이 Core 주문·결제 묶음 상태만 준비한다. 결제·환불을 끝내는 도우미는 #347에서 지웠다. */
public abstract class RefundTestSupport extends CommerceCoreSecurityTestSupport {
    private static final String CORE_SCHEMA =
            "core_refund_" + java.util.UUID.randomUUID().toString().replace("-", "");

    @org.springframework.test.context.DynamicPropertySource
    static void refundDatabase(
            org.springframework.test.context.DynamicPropertyRegistry properties) {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) return;
        properties.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + CORE_SCHEMA);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add(
                "spring.datasource.username",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_USER"));
        properties.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.flyway.schemas", () -> CORE_SCHEMA);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    protected static final String SELLER_B = "44444444-4444-4444-8444-444444444444";
    protected static final String REFUND_COUPON = "55555555-5555-4555-8555-555555555555";
    @Autowired protected PaymentGroupService paymentGroups;
    @Autowired protected PaymentGroupRepository groups;
    @Autowired protected CartService cart;
    @Autowired protected CartItemRepository cartItems;
    @Autowired protected OrderJpaRepository orders;
    @Autowired protected SalesJpaRepository sales;
    @Autowired protected SalesStockJpaRepository stocks;
    @Autowired protected InMemoryProductLookupStub productLookup;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PaymentDeliveryStore deliveries;
    protected CartItem a;
    protected CartItem b;

    @BeforeEach
    void seedRefundFixtures() {
        cleanRefundFixtures();
        productLookup.register(new ProductSnapshot(81001L, "A", null, SELLER));
        productLookup.register(new ProductSnapshot(81002L, "B", null, SELLER_B));
        Long salesA = sales.saveAndFlush(new Sales(81001L, 10000L, SalesStatus.ON_SALE)).getId();
        Long salesB = sales.saveAndFlush(new Sales(81002L, 5000L, SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(salesA, 5, 0));
        stocks.saveAndFlush(new SalesStock(salesB, 5, 0));
        prepareLegacyTradeFixtures();
        a = cart.add(MEMBER_A, 81001L, 2);
        b = cart.add(MEMBER_A, 81002L, 1);
    }

    @AfterEach
    void cleanRefundFixtures() {
        if (jdbc == null) {
            return;
        }
        jdbc.update("DELETE FROM late_approval_refund");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM payment_delivery_request");
        jdbc.update("DELETE FROM refund_target_order");
        jdbc.update("DELETE FROM refund_request");
        if (orders != null) {
            orders.deleteAll();
        }
        if (groups != null) {
            groups.deleteAll();
        }
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", REFUND_COUPON);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", REFUND_COUPON);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", REFUND_COUPON);
        if (cartItems != null) {
            cartItems.deleteAll();
        }
        if (stocks != null) {
            stocks.deleteAll();
        }
        if (sales != null) {
            sales.deleteAll();
        }
        jdbc.update("DELETE FROM member_purchase_guard");
        if (productLookup != null) {
            productLookup.clear();
        }
    }
}
