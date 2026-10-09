package com.shoppinglive.commerce.refunds;

import com.shoppinglive.commerce.cart.application.CartService;
import com.shoppinglive.commerce.cart.domain.CartItem;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.application.MockPaymentEngine;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

abstract class RefundTestSupport extends CommerceSecurityTestSupport {
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
    @Autowired protected InMemoryShoppingClientStub shopping;
    @Autowired protected JdbcTemplate jdbc;
    @MockitoBean protected MockPaymentEngine engine;
    protected CartItem a;
    protected CartItem b;

    @BeforeEach
    void seedRefundFixtures() {
        cleanRefundFixtures();
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
        if (jdbc == null) return;
        jdbc.update("DELETE FROM mock_refund_result");
        jdbc.update("DELETE FROM refund_target_order");
        jdbc.update("DELETE FROM refund_request");
        jdbc.update("DELETE FROM mock_gateway_result");
        if (attempts != null) attempts.deleteAll();
        if (orders != null) orders.deleteAll();
        if (groups != null) groups.deleteAll();
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", REFUND_COUPON);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", REFUND_COUPON);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", REFUND_COUPON);
        if (cartItems != null) cartItems.deleteAll();
        if (stocks != null) stocks.deleteAll();
        if (sales != null) sales.deleteAll();
        jdbc.update("DELETE FROM member_purchase_guard");
        if (shopping != null) shopping.clear();
    }

    protected PaymentGroupService.GroupResponse createAndPay() {
        var created = paymentGroups.create(MEMBER_A,
            java.util.List.of(new PaymentGroupService.Selection(a.getId(), a.getVersion()),
                new PaymentGroupService.Selection(b.getId(), b.getVersion())),
            "구매자", "01012345678", 25000L, "refund-order").group();
        var attempt = paymentGroups.start(MEMBER_A, created.groupNumber(), "refund-payment");
        if (attempt.getStatus() != PaymentStatus.SUCCESS) paymentGroups.resolve(attempt.getId());
        return paymentGroups.get(MEMBER_A, created.groupNumber());
    }

    protected PaymentGroupService.GroupResponse createAndPayWithCoupon() {
        var now = java.time.Instant.now();
        jdbc.update("""
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                starts_at,ends_at,expires_at,created_at)
            VALUES (?,?,?,1000,1,1,?,?,?,?)
            """, REFUND_COUPON, SELLER, "환불 테스트 쿠폰", java.sql.Timestamp.from(now.minusSeconds(60)),
            java.sql.Timestamp.from(now.plusSeconds(3600)), java.sql.Timestamp.from(now.plusSeconds(7200)),
            java.sql.Timestamp.from(now));
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", REFUND_COUPON, 81001L);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)", REFUND_COUPON, 81002L);
        jdbc.update("""
            INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at)
            VALUES (?,?,?,'AVAILABLE',?)
            """, java.util.UUID.randomUUID().toString(), REFUND_COUPON, MEMBER_A,
            java.sql.Timestamp.from(now));
        var created = paymentGroups.create(MEMBER_A,
            java.util.List.of(new PaymentGroupService.Selection(a.getId(), a.getVersion()),
                new PaymentGroupService.Selection(b.getId(), b.getVersion())),
            "구매자", "01012345678", 25000L, REFUND_COUPON, "refund-coupon-order").group();
        var attempt = paymentGroups.start(MEMBER_A, created.groupNumber(), "refund-coupon-payment");
        if (attempt.getStatus() != PaymentStatus.SUCCESS) paymentGroups.resolve(attempt.getId());
        return paymentGroups.get(MEMBER_A, created.groupNumber());
    }
}
