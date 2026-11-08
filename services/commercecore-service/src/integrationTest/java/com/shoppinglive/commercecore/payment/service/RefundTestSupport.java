package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.CartItem;
import com.shoppinglive.commercecore.order.repository.CartItemRepository;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.order.service.CartService;
import com.shoppinglive.commercecore.order.service.PaymentGroupService;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport;
import com.shoppinglive.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.payment.service.MockPaymentEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
    @Autowired protected PaymentAttemptJpaRepository attempts;
    @Autowired protected SalesJpaRepository sales;
    @Autowired protected SalesStockJpaRepository stocks;
    @Autowired protected InMemoryProductLookupStub shopping;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PaymentClientTestConfiguration.BoundaryPaymentServer paymentServer;
    @Autowired protected PaymentDeliveryStore deliveries;
    @Autowired protected PaymentDispatcher dispatcher;
    @MockitoBean protected MockPaymentEngine engine;
    protected CartItem a;
    protected CartItem b;

    @BeforeEach
    void seedRefundFixtures() {
        cleanRefundFixtures();
        paymentServer.reset();
        shopping.register(new ProductSnapshot(81001L, "A", null, SELLER));
        shopping.register(new ProductSnapshot(81002L, "B", null, SELLER_B));
        Long salesA = sales.saveAndFlush(new Sales(81001L, 10000L, SalesStatus.ON_SALE)).getId();
        Long salesB = sales.saveAndFlush(new Sales(81002L, 5000L, SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(salesA, 5, 0));
        stocks.saveAndFlush(new SalesStock(salesB, 5, 0));
        a = cart.add(MEMBER_A, 81001L, 2);
        b = cart.add(MEMBER_A, 81002L, 1);
    }

    @AfterEach
    void cleanRefundFixtures() {
        if (jdbc == null) {
            return;
        }
        jdbc.update("DELETE FROM event_outbox");
        paymentServer.jdbc.update("DELETE FROM mock_refund_result");
        paymentServer.jdbc.update("DELETE FROM refund_execution");
        jdbc.update("DELETE FROM payment_delivery_request");
        jdbc.update("DELETE FROM refund_target_order");
        jdbc.update("DELETE FROM refund_request");
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        if (attempts != null) {
            attempts.deleteAll();
        }
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
        if (shopping != null) {
            shopping.clear();
        }
    }

    protected PaymentGroupService.GroupResponse createAndPay() {
        var created =
                paymentGroups
                        .create(
                                MEMBER_A,
                                java.util.List.of(
                                        new PaymentGroupService.Selection(
                                                a.getId(), a.getVersion()),
                                        new PaymentGroupService.Selection(
                                                b.getId(), b.getVersion())),
                                "구매자",
                                "01012345678",
                                25000L,
                                "refund-order")
                        .group();
        var attempt = paymentGroups.start(MEMBER_A, created.groupNumber(), "refund-payment");
        if (attempt.getStatus() != PaymentStatus.SUCCESS) {
            paymentGroups.resolve(attempt.getId());
        }
        return paymentGroups.get(MEMBER_A, created.groupNumber());
    }

    protected void submitRefund(long id) {
        var delivery = deliveries.byRefund(id);
        paymentClient.refund(
                deliveries.read(
                        delivery.command(),
                        com.shoppinglive.commercecore.payment.dto.RefundCommand.class));
    }

    @Autowired protected PaymentClient paymentClient;

    protected void makeRefundDue(long requestId) {
        paymentServer.jdbc.update(
                "UPDATE refund_execution SET next_action_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                requestId);
    }

    protected PaymentGroupService.GroupResponse createAndPayWithCoupon() {
        return createAndPayWithCoupon(1000);
    }

    protected PaymentGroupService.GroupResponse createAndPayWithCoupon(long discount) {
        var now = java.time.Instant.now();
        jdbc.update(
                """
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                starts_at,ends_at,expires_at,created_at)
            VALUES (?,?,?,?,1,1,?,?,?,?)
            """,
                REFUND_COUPON,
                SELLER,
                "환불 테스트 쿠폰",
                discount,
                java.sql.Timestamp.from(now.minusSeconds(60)),
                java.sql.Timestamp.from(now.plusSeconds(3600)),
                java.sql.Timestamp.from(now.plusSeconds(7200)),
                java.sql.Timestamp.from(now));
        jdbc.update(
                "INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)",
                REFUND_COUPON,
                81001L);
        jdbc.update(
                "INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)",
                REFUND_COUPON,
                81002L);
        jdbc.update(
                """
            INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at)
            VALUES (?,?,?,'AVAILABLE',?)
            """,
                java.util.UUID.randomUUID().toString(),
                REFUND_COUPON,
                MEMBER_A,
                java.sql.Timestamp.from(now));
        var created =
                paymentGroups
                        .create(
                                MEMBER_A,
                                java.util.List.of(
                                        new PaymentGroupService.Selection(
                                                a.getId(), a.getVersion()),
                                        new PaymentGroupService.Selection(
                                                b.getId(), b.getVersion())),
                                "구매자",
                                "01012345678",
                                25000L,
                                REFUND_COUPON,
                                "refund-coupon-order")
                        .group();
        var attempt = paymentGroups.start(MEMBER_A, created.groupNumber(), "refund-coupon-payment");
        if (attempt.getStatus() != PaymentStatus.SUCCESS) {
            paymentGroups.resolve(attempt.getId());
        }
        return paymentGroups.get(MEMBER_A, created.groupNumber());
    }
}
