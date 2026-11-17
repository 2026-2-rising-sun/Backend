package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commercecore.cart.entity.CartItem;
import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.cart.service.CartService;
import com.shoppinglive.commercecore.order.dto.CreateOrderCommand;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.order.service.PaymentGroupService.Selection;
import com.shoppinglive.commercecore.payment.service.PaymentService;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** 테스트 전체를 감싸는 트랜잭션은 사용하지 않는다. 결제 승인과 롤백 경계를 실제 커밋으로 확인한다. */
@SpringBootTest(properties = "shoppinglive.observability.enabled=true")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@AutoConfigureMockMvc
class PaymentGroupIntegrationTest
        extends com.shoppinglive.commercecore.payment.service.PaymentDatabaseTestSupport {
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired MockMvc mvc;
    @Autowired PaymentGroupService service;
    @Autowired PaymentGroupRepository groups;
    @Autowired CartService cart;
    @Autowired CartItemRepository items;
    @Autowired OrderCreationService singleCreation;
    @Autowired OrderService singleOrders;
    @Autowired PaymentService singlePayments;
    @Autowired OrderJpaRepository orders;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @MockitoSpyBean com.shoppinglive.commercecore.sku.service.SkuService skuCommands;
    @Autowired InMemoryProductLookupStub productLookup;
    @Autowired JdbcTemplate jdbc;

    private Long salesA;
    private Long salesB;
    private CartItem a;
    private CartItem b;
    private CartItem unselected;
    private static final String COUPON_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    @BeforeEach
    void seed() {
        clean();
        productLookup.register(new ProductSnapshot(1L, "A", null));
        productLookup.register(new ProductSnapshot(2L, "B", null));
        productLookup.register(new ProductSnapshot(3L, "미선택", null));
        salesA = sales.saveAndFlush(new Sales(1L, 10000L, SalesStatus.ON_SALE)).getId();
        salesB = sales.saveAndFlush(new Sales(2L, 5000L, SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(salesA, 10, 0));
        stocks.saveAndFlush(new SalesStock(salesB, 10, 0));
        var unselectedSales = sales.saveAndFlush(new Sales(3L, 1000L, SalesStatus.ON_SALE));
        stocks.saveAndFlush(new SalesStock(unselectedSales.getId(), 10, 0));
        prepareLegacyTradeFixtures();
        a = cart.add(MEMBER_A, 1L, 2);
        b = cart.add(MEMBER_A, 2L, 1);
        unselected = cart.add(MEMBER_A, 3L, 1);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_delivery_request");
        orders.deleteAll();
        groups.deleteAll();
        items.deleteAll();
        ensureCouponTables();
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", COUPON_ID);
        stocks.deleteAll();
        sales.deleteAll();
        jdbc.update("DELETE FROM member_purchase_guard");
        productLookup.clear();
    }

    private void ensureCouponTables() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS coupon_definition (id VARCHAR(36) PRIMARY KEY, seller_id VARCHAR(36) NOT NULL, name VARCHAR(100) NOT NULL, fixed_discount BIGINT NOT NULL, issuance_limit INTEGER NOT NULL, issued_count INTEGER NOT NULL, starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS coupon_target (coupon_id VARCHAR(36) NOT NULL, product_id BIGINT NOT NULL, PRIMARY KEY(coupon_id, product_id))");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS member_coupon (id VARCHAR(36) PRIMARY KEY, coupon_id VARCHAR(36) NOT NULL, member_id VARCHAR(36) NOT NULL, status VARCHAR(16) NOT NULL, claimed_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(coupon_id, member_id))");
    }

    private List<Selection> selected() {
        return List.of(
                new Selection(a.getId(), a.getVersion()), new Selection(b.getId(), b.getVersion()));
    }

    private PaymentGroupService.Creation create(String key) {
        return service.create(MEMBER_A, selected(), "회원", "01012345678", 25000, key);
    }

    @Test
    @DisplayName("묶음 생성과 취소는 재요청에도 업무 저장과 함께 한 번씩 기록한다")
    void recordsCreationAndCancellationOnce() throws Exception {
        double before = count("shoppinglive.domain.events", "event_type", "PaymentGroupCreated");
        double replayBefore = count("shoppinglive.order.requests", "result", "replayed");
        var result = create("notification-events");
        assertThat(create("notification-events").created()).isFalse();
        assertThat(
                        count("shoppinglive.domain.events", "event_type", "PaymentGroupCreated")
                                - before)
                .isEqualTo(1);
        assertThat(count("shoppinglive.order.requests", "result", "replayed") - replayBefore)
                .isEqualTo(1);
        String number = result.group().groupNumber();
        service.cancel(MEMBER_A, number);
        var rows =
                jdbc.queryForList(
                        "SELECT payload FROM event_outbox WHERE message_key=? ORDER BY id",
                        String.class,
                        number);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(rows).hasSize(2);
        var created = mapper.readTree(rows.get(0));
        var cancelled = mapper.readTree(rows.get(1));
        assertThat(created.path("eventType").asText()).isEqualTo("PaymentGroupCreated");
        assertThat(cancelled.path("eventType").asText()).isEqualTo("OrderCancelled");
        assertThat(created.at("/payload/memberId").asText()).isEqualTo(MEMBER_A);
        assertThat(created.at("/payload/orders")).hasSize(2);
        assertThat(cancelled.at("/payload/cancelledAmount").asLong()).isEqualTo(25000);
        var directory = java.nio.file.Path.of("build/contract-samples");
        java.nio.file.Files.createDirectories(directory);
        for (String row : rows) {
            var event = mapper.readTree(row);
            String type = event.get("eventType").asText();
            mapper.writeValue(
                    directory.resolve(type + "-actual.json").toFile(),
                    java.util.Map.of(
                            "contract",
                            "commerce-payment-group.yaml",
                            "message",
                            type,
                            "payload",
                            event));
        }
    }

    private double count(String name, String label, String value) {
        return metrics.find(name).tag(label, value).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private void stock(Long id, int available, int reserved) {
        var current = legacyStock(id);
        assertThat(current.getAvailable()).isEqualTo(available);
        assertThat(current.getReserved()).isEqualTo(reserved);
    }

    private void issueCoupon() {
        Instant now = Instant.now();
        jdbc.update(
                """
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,version,created_at)
            VALUES(?,?,?, ?,10,1,?,?,?,0,?)
            """,
                COUPON_ID,
                MEMBER_B,
                "5천원 할인",
                5000L,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.plusSeconds(7200)),
                Timestamp.from(now));
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES(?,1)", COUPON_ID);
        jdbc.update(
                "INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES(?,?,?,'AVAILABLE',?)",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                COUPON_ID,
                MEMBER_A,
                Timestamp.from(now.minusSeconds(30)));
    }

    private String couponStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM member_coupon WHERE coupon_id=? AND member_id=?",
                String.class,
                COUPON_ID,
                MEMBER_A);
    }

    private void untouched() {
        assertThat(groups.count()).isZero();
        assertThat(orders.count()).isZero();
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 10, 0);
        stock(salesB, 10, 0);
    }

    @DisplayName("두 상품의 견적 25000원으로 주문 두 건과 예약된 결제 묶음 하나를 생성한다")
    @Test
    void twoProductsCreateTwoOrdersWith25000QuoteAndOneReservedGroup() {
        var quote = service.preview(MEMBER_A, selected());
        assertThat(quote.totalAmount()).isEqualTo(25000);
        assertThat(quote.items())
                .extracting(PaymentGroupService.Item::totalAmount)
                .containsExactly(20000L, 5000L);
        var result = create("create");
        assertThat(result.created()).isTrue();
        assertThat(result.group().totalAmount()).isEqualTo(25000);
        assertThat(result.group().orders()).hasSize(2);
        assertThat(result.group().orders())
                .extracting(order -> order.groupNumber())
                .containsOnly(result.group().groupNumber());
        assertThat(service.active(MEMBER_A)).isPresent();
        assertThat(service.active(MEMBER_B)).isEmpty();
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 8, 2);
        stock(salesB, 9, 1);
    }

    @DisplayName("쿠폰 할인 스냅샷을 저장하고 재요청에도 한 번만 예약한다")
    @Test
    void couponIsReservedWithDiscountSnapshotsAndIdempotentReplay() {
        issueCoupon();
        var quote = service.preview(MEMBER_A, selected(), COUPON_ID);
        assertThat(quote.discountAmount()).isEqualTo(5000);
        assertThat(quote.payableAmount()).isEqualTo(20000);
        assertThat(quote.items())
                .extracting(PaymentGroupService.Item::discountAmount)
                .containsExactly(5000L, 0L);

        var created =
                service.create(
                        MEMBER_A,
                        selected(),
                        "회원",
                        "01012345678",
                        25000,
                        COUPON_ID,
                        "coupon-order");
        assertThat(created.group().couponId()).isEqualTo(COUPON_ID);
        assertThat(created.group().discountAmount()).isEqualTo(5000);
        assertThat(created.group().payableAmount()).isEqualTo(20000);
        assertThat(orders.findAll())
                .extracting(order -> order.getDiscountAmount())
                .containsExactlyInAnyOrder(5000L, 0L);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(
                        service.create(
                                        MEMBER_A,
                                        selected(),
                                        "회원",
                                        "01012345678",
                                        25000,
                                        COUPON_ID,
                                        "coupon-order")
                                .created())
                .isFalse();
    }

    @DisplayName("전액 할인 묶음은 0원 견적으로 생성하고 쿠폰을 예약한다")
    @Test
    void fullyDiscountedGroupIsCreatedWithZeroPayableAndReservedCoupon() {
        issueCoupon();
        jdbc.update("UPDATE coupon_definition SET fixed_discount=? WHERE id=?", 25000L, COUPON_ID);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES(?,2)", COUPON_ID);

        var quote = service.preview(MEMBER_A, selected(), COUPON_ID);
        assertThat(quote.totalAmount()).isEqualTo(25000L);
        assertThat(quote.discountAmount()).isEqualTo(25000L);
        assertThat(quote.payableAmount()).isZero();

        var created =
                service.create(
                        MEMBER_A, selected(), "회원", "01012345678", 25000, COUPON_ID, "zero-order");
        assertThat(created.group().payableAmount()).isZero();
        assertThat(created.group().orders())
                .extracting(order -> order.payableAmount())
                .containsOnly(0L);
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @DisplayName("취소·만료 시 쿠폰 예약을 해제한다")
    @Test
    void couponReservationIsReleasedOnCancellationAndExpiry() {
        issueCoupon();
        var cancelled =
                service.create(
                                MEMBER_A,
                                selected(),
                                "회원",
                                "01012345678",
                                25000,
                                COUPON_ID,
                                "coupon-cancel")
                        .group();
        service.cancel(MEMBER_A, cancelled.groupNumber());
        assertThat(couponStatus()).isEqualTo("AVAILABLE");

        var expired =
                service.create(
                                MEMBER_A,
                                selected(),
                                "회원",
                                "01012345678",
                                25000,
                                COUPON_ID,
                                "coupon-expiry")
                        .group();
        Long groupId =
                groups.findByGroupNumberAndMemberId(expired.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        jdbc.update(
                "UPDATE payment_group SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                groupId);
        assertThat(service.expire(groupId)).isTrue();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
    }

    @DisplayName("단건 주문은 쿠폰 할인 스냅샷을 저장하고 쿠폰을 예약한다")
    @Test
    void singleOrderCouponIsReservedAndSnapshotted() {
        issueCoupon();
        var order = singleCouponOrder("single-coupon-success").order();
        assertThat(order.getTotalAmount()).isEqualTo(10000L);
        assertThat(order.getDiscountAmount()).isEqualTo(5000L);
        assertThat(order.getPayableAmount()).isEqualTo(5000L);
        assertThat(order.getPaymentGroup().getCouponId()).isEqualTo(COUPON_ID);
        assertThat(order.getPaymentGroup().getDiscountAmount()).isEqualTo(5000L);
        assertThat(order.getPaymentGroup().getPayableAmount()).isEqualTo(5000L);
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @DisplayName("단건 주문 취소·만료 시 쿠폰 예약을 해제한다")
    @Test
    void singleOrderCouponReservationIsReleasedByCancellationAndExpiry() {
        issueCoupon();
        var cancelled = singleCouponOrder("single-coupon-cancel").order();
        singleOrders.cancelBeforePayment(cancelled.getOrderNumber(), MEMBER_A);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");

        var expired = singleCouponOrder("single-coupon-expiry").order();
        Long groupId = expired.getPaymentGroup().getId();
        jdbc.update(
                "UPDATE payment_group SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                groupId);
        assertThat(service.expire(groupId)).isTrue();
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        stock(salesA, 10, 0);
    }

    @DisplayName("전액 할인 단건 주문은 0원으로 생성하고 쿠폰을 예약한다")
    @Test
    void singleOrderFullyDiscountedCouponIsCreatedWithZeroPayable() {
        issueCoupon();
        jdbc.update("UPDATE coupon_definition SET fixed_discount=? WHERE id=?", 10000L, COUPON_ID);
        var created = singleCouponOrder("single-coupon-zero").order();
        assertThat(created.getPayableAmount()).isZero();
        assertThat(couponStatus()).isEqualTo("RESERVED");
    }

    @DisplayName("단건 주문의 결제 결과가 미확정이면 쿠폰 예약을 유지한다")
    @Test
    void singleOrderCouponRemainsReservedWhilePaymentOutcomeIsUnresolved() {
        issueCoupon();
        var created = singleCouponOrder("single-coupon-pending").order();

        // Payment에 닿지 않아 결과가 없으므로 미확정 상태가 유지된다.
        var attempt = singlePayments.startPayment(created.getOrderNumber(), MEMBER_A);

        assertThat(attempt.getStatus().name()).isEqualTo("PROCESSING");
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(service.get(MEMBER_A, created.getPaymentGroup().getGroupNumber()).status())
                .isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
    }

    private com.shoppinglive.commercecore.order.dto.OrderCreationResult singleCouponOrder(
            String key) {
        return singleCreation.create(
                new CreateOrderCommand(
                        1L, 1, "회원", "01012345678", MEMBER_A, 10000L, null, null, COUPON_ID),
                key);
    }

    @DisplayName("후속 재고 예약에 실패하면 쿠폰 예약도 롤백한다")
    @Test
    void couponReservationRollsBackWhenLaterStockReservationFails() {
        issueCoupon();
        org.mockito.Mockito.doThrow(
                        new com.shoppinglive.commercecore.sales.exception
                                .InsufficientStockException("두 번째 SKU 예약 실패 주입"))
                .when(
                        (com.shoppinglive.commercecore.sku.service.SkuService)
                                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                        skuCommands))
                .reserve(salesB, 1);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        MEMBER_A,
                                        selected(),
                                        "회원",
                                        "01012345678",
                                        25000,
                                        COUPON_ID,
                                        "coupon-stock-race"))
                .isInstanceOf(BusinessException.class);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        untouched();
    }

    @DisplayName("주문 재전송은 한 번만 예약하고 활성 묶음이 있으면 새 묶음·단건 주문을 거절한다")
    @Test
    void createReplayReservesOnceAndActiveGroupBlocksNewGroupAndSingleOrder() {
        var first = create("same");
        var replay =
                service.create(
                        MEMBER_A,
                        List.of(selected().getLast(), selected().getFirst()),
                        "회원",
                        "01012345678",
                        25000,
                        "same");
        assertThat(replay.created()).isFalse();
        assertThat(replay.group().groupNumber()).isEqualTo(first.group().groupNumber());
        assertThatThrownBy(() -> create("different")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        MEMBER_A, selected(), "변경", "01012345678", 25000, "same"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                singleCreation.create(
                                        new CreateOrderCommand(
                                                1L, 1, "회원", "01012345678", MEMBER_A, 10000L),
                                        "single"))
                .isInstanceOf(BusinessException.class);
        assertThat(groups.count()).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(2);
        stock(salesA, 8, 2);
        stock(salesB, 9, 1);
    }

    @DisplayName("활성 단건 주문은 새 묶음을 막고 기존 하위 주문 경로로 묶음을 일부 결제·취소할 수 없다")
    @Test
    void activeSingleOrderBlocksGroupAndLegacyChildActionsCannotPartiallyPayOrCancelGroup() {
        var single =
                singleCreation.create(
                        new CreateOrderCommand(1L, 1, "회원", "01012345678", MEMBER_A, 10000L),
                        "single");
        assertThatThrownBy(() -> create("group")).isInstanceOf(BusinessException.class);
        singleOrders.cancelBeforePayment(single.order().getOrderNumber(), MEMBER_A);
        var group = create("group").group();
        String child = group.orders().getFirst().orderNumber();
        assertThatThrownBy(() -> singlePayments.startPayment(child, MEMBER_A))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> singleOrders.cancelBeforePayment(child, MEMBER_A))
                .isInstanceOf(BusinessException.class);
        assertThat(service.get(MEMBER_A, group.groupNumber()).status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        stock(salesA, 8, 2);
        stock(salesB, 9, 1);
    }

    @DisplayName("소유권·중복 선택·버전·가격·재고 검증에 실패하면 구매를 부분 저장하지 않는다")
    @Test
    void ownershipDuplicateSelectionStaleVersionPriceAndInsufficientStockLeaveNoPartialPurchase() {
        assertThatThrownBy(() -> service.preview(MEMBER_B, selected()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MEMBER_A,
                                        List.of(selected().getFirst(), selected().getFirst())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        MEMBER_A,
                                        List.of(new Selection(a.getId(), a.getVersion() + 1)),
                                        "회원",
                                        "01012345678",
                                        20000,
                                        "stale"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        MEMBER_A, selected(), "회원", "01012345678", 24999, "price"))
                .isInstanceOf(BusinessException.class);
        assertThat(service.preview(MEMBER_A, selected()).totalAmount()).isEqualTo(25000);
        jdbc.update("UPDATE sku SET price=11000 WHERE id=?", salesA);
        assertThatThrownBy(() -> create("price-changed-after-quote"))
                .isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE sku SET price=10000 WHERE id=?", salesA);
        cart.changeQuantity(MEMBER_A, b.getId(), 11);
        assertThatThrownBy(
                        () ->
                                service.preview(
                                        MEMBER_A,
                                        List.of(
                                                new Selection(a.getId(), a.getVersion()),
                                                new Selection(
                                                        b.getId(),
                                                        items.findById(b.getId())
                                                                .orElseThrow()
                                                                .getVersion()))))
                .isInstanceOf(BusinessException.class);
        untouched();
    }

    @DisplayName("두 번째 재고 예약에 실패하면 첫 상품 예약과 전체 주문을 롤백한다")
    @Test
    void secondConditionalReservationFailureRollsBackAlreadyReservedFirstProductAndOrders() {
        double before = count("shoppinglive.order.requests", "result", "created");
        double events = count("shoppinglive.domain.events", "event_type", "PaymentGroupCreated");
        // 주문서 조회 이후 재고가 줄어든 상황을 만들어 첫 항목의 실제 예약 이후 실패하도록 한다.
        org.mockito.Mockito.doThrow(
                        new com.shoppinglive.commercecore.sales.exception
                                .InsufficientStockException("두 번째 SKU 예약 실패 주입"))
                .when(
                        (com.shoppinglive.commercecore.sku.service.SkuService)
                                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                        skuCommands))
                .reserve(salesB, 1);
        assertThatThrownBy(() -> create("race")).isInstanceOf(BusinessException.class);
        untouched();
        assertThat(count("shoppinglive.order.requests", "result", "created")).isEqualTo(before);
        assertThat(count("shoppinglive.domain.events", "event_type", "PaymentGroupCreated"))
                .isEqualTo(events);
    }

    @DisplayName("묶음 취소는 모든 상품 재고를 한 번만 복구하고 장바구니를 유지한다")
    @Test
    void cancelRestoresEveryProductExactlyOnceAndKeepsAllCartItems() {
        var group = create("cancel").group();
        assertThatThrownBy(() -> service.cancel(MEMBER_B, group.groupNumber()))
                .isInstanceOf(BusinessException.class);
        service.cancel(MEMBER_A, group.groupNumber());
        service.cancel(MEMBER_A, group.groupNumber());
        assertThat(service.get(MEMBER_A, group.groupNumber()).status())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findAll())
                .extracting(order -> order.getStatus())
                .containsOnly(OrderStatus.CANCELLED);
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 10, 0);
        stock(salesB, 10, 0);
    }

    @DisplayName("만료는 예약 재고를 한 번만 복구하고 결제 확인 중인 묶음은 만료시키지 않는다")
    @Test
    void expiryRestoresAllReservedStockOnceAndDoesNotExpireConfirmingGroups() {
        var group = create("expiry").group();
        Long id =
                groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A)
                        .orElseThrow()
                        .getId();
        assertThat(service.expire(id)).isFalse();
        jdbc.update(
                "UPDATE payment_group SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                id);
        assertThat(service.expire(id)).isTrue();
        assertThat(service.expire(id)).isFalse();
        assertThat(service.get(MEMBER_A, group.groupNumber()).status())
                .isEqualTo(OrderStatus.EXPIRED);
        stock(salesA, 10, 0);
        stock(salesB, 10, 0);
        var confirming = create("confirming").group();
        var attempt = service.start(MEMBER_A, confirming.groupNumber(), "pay");
        jdbc.update(
                "UPDATE payment_group SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                attempt.getPaymentGroupId());
        assertThat(service.expire(attempt.getPaymentGroupId())).isFalse();
        stock(salesA, 8, 2);
        stock(salesB, 9, 1);
    }

    @DisplayName("같은 키로 동시에 요청해도 결제 묶음과 재고 예약은 한 번만 생성한다")
    @Test
    void concurrentSameKeyCreatesOneGroupAndReservesOnce() throws Exception {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var numbers = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                numbers.add(create("concurrent").group().groupNumber());
                            } catch (Throwable failure) {
                                failures.add(failure);
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).isEmpty();
        assertThat(numbers).hasSize(4);
        assertThat(numbers.stream().distinct().count()).isEqualTo(1);
        assertThat(groups.count()).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(2);
        stock(salesA, 8, 2);
        stock(salesB, 9, 1);
    }

    @DisplayName("단건·묶음 동시 주문은 같은 DB 구매 잠금을 공유한다")
    @Test
    void concurrentSingleAndGroupCreationShareOneDatabasePurchaseGuard() throws Exception {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var accepted = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            accepted.add(create("race-group").group().groupNumber());
                        } catch (Throwable failure) {
                            failures.add(failure);
                        } finally {
                            done.countDown();
                        }
                    });
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            accepted.add(
                                    singleCreation
                                            .create(
                                                    new CreateOrderCommand(
                                                            1L,
                                                            1,
                                                            "회원",
                                                            "01012345678",
                                                            MEMBER_A,
                                                            10000L),
                                                    "race-single")
                                            .order()
                                            .getOrderNumber());
                        } catch (Throwable failure) {
                            failures.add(failure);
                        } finally {
                            done.countDown();
                        }
                    });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(accepted).hasSize(1);
        assertThat(failures)
                .hasSize(1)
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class));
        assertThat(groups.count()).isEqualTo(1);
        var winner = service.active(MEMBER_A).orElseThrow();
        if (winner.totalAmount() == 25000) {
            assertThat(orders.count()).isEqualTo(2);
            stock(salesA, 8, 2);
            stock(salesB, 9, 1);
        } else {
            assertThat(winner.totalAmount()).isEqualTo(10000);
            assertThat(orders.count()).isEqualTo(1);
            stock(salesA, 9, 1);
            stock(salesB, 10, 0);
        }
    }
}
