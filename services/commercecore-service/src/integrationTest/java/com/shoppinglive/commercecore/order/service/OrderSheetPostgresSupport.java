package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.service.*;
import com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
abstract class OrderSheetPostgresSupport extends CommerceCoreSecurityTestSupport {
    static final String SCHEMA = "order_sheet_" + UUID.randomUUID().toString().replace("-", "");
    static final String COUPON = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("COMMERCECORE_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        p.add("spring.datasource.username", () -> System.getenv("COMMERCECORE_TEST_POSTGRES_USER"));
        p.add(
                "spring.datasource.password",
                () -> System.getenv("COMMERCECORE_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.enabled", () -> true);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void requireIndependentPaymentDatabase() {
        Assumptions.assumeTrue(
                System.getenv("PAYMENT_TEST_POSTGRES_URL") != null,
                "독립 Payment PostgreSQL 설정이 필요합니다.");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderSheetService sheets;
    @Autowired OrderSheetLifecycleService lifecycle;
    @Autowired OrderSheetRefundService refunds;
    @Autowired PaymentGroupService legacyGroups;
    @Autowired PaymentGroupRepository groups;
    @Autowired PaymentDeliveryStore deliveries;
    @Autowired PaymentDispatcher dispatcher;
    @Autowired PaymentClient client;
    @Autowired PaymentResultHandler handler;
    @Autowired OrderClosureDeliveryService closures;
    @Autowired RefundBoundaryTestDriver refundDriver;
    @Autowired PaymentClientTestConfiguration.BoundaryPaymentServer paymentServer;
    @Autowired MockMvc mvc;

    @BeforeEach
    void seedOrderSheetData() {
        for (String table :
                List.of(
                        "refund_target_order",
                        "payment_delivery_request",
                        "refund_request",
                        "order_payment_closure",
                        "orders",
                        "payment_group",
                        "member_purchase_guard",
                        "member_coupon",
                        "coupon_target",
                        "coupon_definition",
                        "cart_item",
                        "sku",
                        "sales_stock",
                        "sales_info",
                        "product",
                        "product_image",
                        "event_outbox")) jdbc.update("DELETE FROM " + table);
        for (String table :
                List.of(
                        "mock_refund_result",
                        "refund_execution",
                        "mock_gateway_result",
                        "payment_order_control",
                        "payment_attempt",
                        "event_outbox")) paymentServer.jdbc.update("DELETE FROM " + table);
        paymentServer.reset();
        for (long product : List.of(101L, 102L))
            jdbc.update(
                    "INSERT INTO product(id,name,description,main_image_id,seller_id,category,sale_enabled,version,created_at,updated_at) VALUES (?,?,'설명',1,?,'FASHION',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    product,
                    "상품" + product,
                    SELLER);
        seedSku(501, 101, 4000, 20);
        seedSku(502, 102, 3000, 20);
        seedSku(503, 101, 5000, 20);
        jdbc.update(
                "INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,version,created_at) VALUES (?,?,'쿠폰',2000,10,1,CURRENT_TIMESTAMP-INTERVAL '1 day',CURRENT_TIMESTAMP+INTERVAL '1 day',CURRENT_TIMESTAMP+INTERVAL '2 days',0,CURRENT_TIMESTAMP)",
                COUPON,
                SELLER);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,101)", COUPON);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,102)", COUPON);
        jdbc.update(
                "INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),
                COUPON,
                MEMBER_A);
    }

    void seedSku(long id, long product, long price, int available) {
        jdbc.update(
                "INSERT INTO sku(id,product_id,price,available,reserved,sale_status,version,snapshot_version,product_name_snapshot,created_at,updated_at) VALUES (?,?,?,?,0,'ON_SALE',0,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,
                product,
                price,
                available,
                "상품" + product);
    }

    OrderSheetResponse create(int quantity) {
        return create(
                UUID.randomUUID().toString(),
                List.of(new CreateOrderSheetRequest.Item(501L, quantity)));
    }

    OrderSheetResponse create(String key, List<CreateOrderSheetRequest.Item> items) {
        return sheets.create(
                        MEMBER_A,
                        key,
                        new CreateOrderSheetRequest(items, null, "구매자", "01012345678"))
                .order();
    }

    OrderSheetResponse createCart(long sku, int quantity) {
        long product =
                jdbc.queryForObject("SELECT product_id FROM sku WHERE id=?", Long.class, sku);
        jdbc.update(
                "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES (?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                MEMBER_A,
                product,
                sku,
                quantity);
        long id =
                jdbc.queryForObject(
                        "SELECT id FROM cart_item WHERE member_id=? AND sku_id=?",
                        Long.class,
                        MEMBER_A,
                        sku);
        return sheets.create(
                        MEMBER_A,
                        UUID.randomUUID().toString(),
                        new CreateOrderSheetRequest(
                                null, List.of(new CartSelection(id, 0L)), "구매자", "01012345678"))
                .order();
    }

    PaymentExecution start(OrderSheetResponse order, boolean coupon) {
        long expected =
                order.totalAmount()
                        - (coupon
                                ? Math.min(
                                        order.items().getFirst().totalAmount(),
                                        jdbc.queryForObject(
                                                "SELECT fixed_discount FROM coupon_definition WHERE id=?",
                                                Long.class,
                                                COUPON))
                                : 0);
        var accepted =
                lifecycle.start(
                        MEMBER_A,
                        order.orderNumber(),
                        "pay:" + order.orderNumber(),
                        expected,
                        coupon ? COUPON : null,
                        coupon ? order.items().getFirst().orderItemId() : null);
        dispatcher.payment(accepted.paymentId());
        return accepted;
    }

    OrderSheetResponse paid(OrderSheetResponse order, boolean coupon) {
        var payment = start(order, coupon);
        paymentServer
                .context
                .getBean(com.shoppinglive.payment.service.PaymentService.class)
                .resolvePayment(payment.paymentId());
        dispatcher.payment(payment.paymentId());
        assertThat(sheets.get(MEMBER_A, order.orderNumber()).status()).isEqualTo("PAID");
        return sheets.get(MEMBER_A, order.orderNumber());
    }

    long groupId(OrderSheetResponse order) {
        return jdbc.queryForObject(
                "SELECT id FROM payment_group WHERE group_number=?",
                Long.class,
                order.orderNumber());
    }

    void makeExpired(OrderSheetResponse order) {
        jdbc.update(
                "UPDATE payment_group SET expires_at=clock_timestamp()-INTERVAL '1 second' WHERE group_number=?",
                order.orderNumber());
    }

    int available(long sku) {
        return jdbc.queryForObject("SELECT available FROM sku WHERE id=?", Integer.class, sku);
    }

    int reserved(long sku) {
        return jdbc.queryForObject("SELECT reserved FROM sku WHERE id=?", Integer.class, sku);
    }

    String couponStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM member_coupon WHERE member_id=? AND coupon_id=?",
                String.class,
                MEMBER_A,
                COUPON);
    }

    OrderSheetRefundService.Creation request(OrderSheetResponse order, int quantity, String key) {
        return refunds.request(
                MEMBER_A,
                order.orderNumber(),
                key,
                new OrderSheetRefundRequest(
                        List.of(
                                new OrderSheetRefundRequest.Item(
                                        order.items().getFirst().orderItemId(), quantity))));
    }

    OrderSheetRefundResponse complete(OrderSheetResponse order, long id) {
        refundDriver.execute(id);
        return refunds.get(MEMBER_A, order.orderNumber(), id);
    }
}
