package com.shoppinglive.commerce.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.cart.application.CartService;
import com.shoppinglive.commerce.cart.domain.CartItem;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.orders.application.CreateOrderCommand;
import com.shoppinglive.commerce.orders.application.OrderCreationService;
import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.application.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.payments.application.MockPaymentEngine;
import com.shoppinglive.commerce.payments.application.PaymentService;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService.Selection;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** No enclosing test transaction: gateway approval and the rollback boundary must really commit. */
@SpringBootTest
class PaymentGroupIntegrationTest extends CommerceSecurityTestSupport {
    @Autowired PaymentGroupService service;
    @Autowired PaymentGroupRepository groups;
    @Autowired CartService cart;
    @Autowired CartItemRepository items;
    @Autowired OrderCreationService singleCreation;
    @Autowired OrderService singleOrders;
    @Autowired PaymentService singlePayments;
    @Autowired OrderJpaRepository orders;
    @Autowired PaymentAttemptJpaRepository payments;
    @Autowired SalesJpaRepository sales;
    @MockitoSpyBean SalesStockJpaRepository stocks;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired DevPaymentScenarioRegistry scenarios;
    @Autowired JdbcTemplate jdbc;
    // Resolve explicitly to assert each committed phase without racing the scheduled callback.
    @MockitoBean MockPaymentEngine engine;
    private Long salesA;
    private Long salesB;
    private CartItem a;
    private CartItem b;
    private CartItem unselected;
    private static final String COUPON_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    @BeforeEach
    void seed() {
        clean();
        shopping.register(new ProductSnapshot(1L, "A", null));
        shopping.register(new ProductSnapshot(2L, "B", null));
        shopping.register(new ProductSnapshot(3L, "미선택", null));
        salesA = sales.saveAndFlush(new Sales(1L, 10000L, SalesStatus.ON_SALE)).getId();
        salesB = sales.saveAndFlush(new Sales(2L, 5000L, SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(salesA, 10, 0));
        stocks.saveAndFlush(new SalesStock(salesB, 10, 0));
        a = cart.add(MEMBER_A, 1L, 2);
        b = cart.add(MEMBER_A, 2L, 1);
        unselected = cart.add(MEMBER_A, 3L, 1);
    }

    @AfterEach
    void clean() {
        if (groups != null) groups.findAll().forEach(group -> scenarios.clear(group.getGroupNumber()));
        jdbc.update("DELETE FROM mock_gateway_result");
        payments.deleteAll(); orders.deleteAll(); groups.deleteAll(); items.deleteAll();
        ensureCouponTables();
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", COUPON_ID);
        stocks.deleteAll(); sales.deleteAll();
        jdbc.update("DELETE FROM member_purchase_guard");
        shopping.clear();
    }

    private void ensureCouponTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS coupon_definition (id VARCHAR(36) PRIMARY KEY, seller_id VARCHAR(36) NOT NULL, name VARCHAR(100) NOT NULL, fixed_discount BIGINT NOT NULL, issuance_limit INTEGER NOT NULL, issued_count INTEGER NOT NULL, starts_at TIMESTAMP WITH TIME ZONE NOT NULL, ends_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS coupon_target (coupon_id VARCHAR(36) NOT NULL, product_id BIGINT NOT NULL, PRIMARY KEY(coupon_id, product_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS member_coupon (id VARCHAR(36) PRIMARY KEY, coupon_id VARCHAR(36) NOT NULL, member_id VARCHAR(36) NOT NULL, status VARCHAR(16) NOT NULL, claimed_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(coupon_id, member_id))");
    }

    private List<Selection> selected() {
        return List.of(new Selection(a.getId(), a.getVersion()), new Selection(b.getId(), b.getVersion()));
    }

    private PaymentGroupService.Creation create(String key) {
        return service.create(MEMBER_A, selected(), "회원", "01012345678", 25000, key);
    }

    private void stock(Long id, int available, int reserved) {
        var current = stocks.findById(id).orElseThrow();
        assertThat(current.getAvailable()).isEqualTo(available);
        assertThat(current.getReserved()).isEqualTo(reserved);
    }

    private void issueCoupon() {
        Instant now = Instant.now();
        jdbc.update("""
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,version,created_at)
            VALUES(?,?,?, ?,10,1,?,?,?,0,?)
            """, COUPON_ID, MEMBER_B, "5천원 할인", 5000L, Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.plusSeconds(3600)), Timestamp.from(now.plusSeconds(7200)), Timestamp.from(now));
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES(?,1)", COUPON_ID);
        jdbc.update("INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES(?,?,?,'AVAILABLE',?)",
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd", COUPON_ID, MEMBER_A, Timestamp.from(now.minusSeconds(30)));
    }

    private String couponStatus() {
        return jdbc.queryForObject("SELECT status FROM member_coupon WHERE coupon_id=? AND member_id=?", String.class, COUPON_ID, MEMBER_A);
    }

    private void untouched() {
        assertThat(groups.count()).isZero();
        assertThat(orders.count()).isZero();
        assertThat(payments.count()).isZero();
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 10, 0); stock(salesB, 10, 0);
    }

    @Test
    void twoProductsCreateTwoOrdersWith25000QuoteAndOneReservedGroup() {
        var quote = service.preview(MEMBER_A, selected());
        assertThat(quote.totalAmount()).isEqualTo(25000);
        assertThat(quote.items()).extracting(PaymentGroupService.Item::totalAmount).containsExactly(20000L, 5000L);
        var result = create("create");
        assertThat(result.created()).isTrue();
        assertThat(result.group().totalAmount()).isEqualTo(25000);
        assertThat(result.group().orders()).hasSize(2);
        assertThat(result.group().orders()).extracting(order -> order.groupNumber()).containsOnly(result.group().groupNumber());
        assertThat(service.active(MEMBER_A)).isPresent();
        assertThat(service.active(MEMBER_B)).isEmpty();
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 8, 2); stock(salesB, 9, 1);
    }

    @Test
    void couponIsReservedWithDiscountSnapshotsAndIdempotentReplay() {
        issueCoupon();
        var quote = service.preview(MEMBER_A, selected(), COUPON_ID);
        assertThat(quote.discountAmount()).isEqualTo(5000);
        assertThat(quote.payableAmount()).isEqualTo(20000);
        assertThat(quote.items()).extracting(PaymentGroupService.Item::discountAmount).containsExactly(5000L, 0L);

        var created = service.create(MEMBER_A, selected(), "회원", "01012345678", 25000, COUPON_ID, "coupon-order");
        assertThat(created.group().couponId()).isEqualTo(COUPON_ID);
        assertThat(created.group().discountAmount()).isEqualTo(5000);
        assertThat(created.group().payableAmount()).isEqualTo(20000);
        assertThat(orders.findAll()).extracting(order -> order.getDiscountAmount()).containsExactlyInAnyOrder(5000L, 0L);
        assertThat(couponStatus()).isEqualTo("RESERVED");
        assertThat(service.create(MEMBER_A, selected(), "회원", "01012345678", 25000, COUPON_ID, "coupon-order").created()).isFalse();

    }

    @Test
    void couponReservationRollsBackWhenLaterStockReservationFails() {
        issueCoupon();
        doReturn(0).when(stocks).reserve(salesB, 1);
        assertThatThrownBy(() -> service.create(MEMBER_A, selected(), "회원", "01012345678", 25000, COUPON_ID, "coupon-stock-race"))
            .isInstanceOf(BusinessException.class);
        assertThat(couponStatus()).isEqualTo("AVAILABLE");
        untouched();
    }

    @Test
    void createReplayReservesOnceAndActiveGroupBlocksNewGroupAndSingleOrder() {
        var first = create("same");
        var replay = service.create(MEMBER_A, List.of(selected().getLast(), selected().getFirst()), "회원", "01012345678", 25000, "same");
        assertThat(replay.created()).isFalse();
        assertThat(replay.group().groupNumber()).isEqualTo(first.group().groupNumber());
        assertThatThrownBy(() -> create("different")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(MEMBER_A, selected(), "변경", "01012345678", 25000, "same")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> singleCreation.create(new CreateOrderCommand(1L, 1, "회원", "01012345678", MEMBER_A, 10000L), "single"))
            .isInstanceOf(BusinessException.class);
        assertThat(groups.count()).isEqualTo(1); assertThat(orders.count()).isEqualTo(2);
        stock(salesA, 8, 2); stock(salesB, 9, 1);
    }

    @Test
    void activeSingleOrderBlocksGroupAndLegacyChildActionsCannotPartiallyPayOrCancelGroup() {
        var single = singleCreation.create(new CreateOrderCommand(1L, 1, "회원", "01012345678", MEMBER_A, 10000L), "single");
        assertThatThrownBy(() -> create("group")).isInstanceOf(BusinessException.class);
        singleOrders.cancelBeforePayment(single.order().getOrderNumber(), MEMBER_A);
        var group = create("group").group();
        String child = group.orders().getFirst().orderNumber();
        assertThatThrownBy(() -> singlePayments.startPayment(child, MEMBER_A)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> singleOrders.cancelBeforePayment(child, MEMBER_A)).isInstanceOf(BusinessException.class);
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payments.count()).isZero();
        stock(salesA, 8, 2); stock(salesB, 9, 1);
    }

    @Test
    void ownershipDuplicateSelectionStaleVersionPriceAndInsufficientStockLeaveNoPartialPurchase() {
        assertThatThrownBy(() -> service.preview(MEMBER_B, selected())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.preview(MEMBER_A, List.of(selected().getFirst(), selected().getFirst())))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(MEMBER_A, List.of(new Selection(a.getId(), a.getVersion() + 1)), "회원", "01012345678", 20000, "stale"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(MEMBER_A, selected(), "회원", "01012345678", 24999, "price"))
            .isInstanceOf(BusinessException.class);
        assertThat(service.preview(MEMBER_A, selected()).totalAmount()).isEqualTo(25000);
        jdbc.update("UPDATE sales_info SET price=11000 WHERE id=?", salesA);
        assertThatThrownBy(() -> create("price-changed-after-quote")).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE sales_info SET price=10000 WHERE id=?", salesA);
        cart.changeQuantity(MEMBER_A, b.getId(), 11);
        assertThatThrownBy(() -> service.preview(MEMBER_A, List.of(new Selection(a.getId(), a.getVersion()),
            new Selection(b.getId(), items.findById(b.getId()).orElseThrow().getVersion())))).isInstanceOf(BusinessException.class);
        untouched();
    }

    @Test
    void secondConditionalReservationFailureRollsBackAlreadyReservedFirstProductAndOrders() {
        // Model stock lost after preview, forcing failure after the first item's real reservation.
        doReturn(0).when(stocks).reserve(salesB, 1);
        assertThatThrownBy(() -> create("race")).isInstanceOf(BusinessException.class);
        untouched();
    }

    @Test
    void cancelRestoresEveryProductExactlyOnceAndKeepsAllCartItems() {
        var group = create("cancel").group();
        assertThatThrownBy(() -> service.cancel(MEMBER_B, group.groupNumber())).isInstanceOf(BusinessException.class);
        service.cancel(MEMBER_A, group.groupNumber());
        service.cancel(MEMBER_A, group.groupNumber());
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.CANCELLED);
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 10, 0); stock(salesB, 10, 0);
    }

    @Test
    void expiryRestoresAllReservedStockOnceAndDoesNotExpireConfirmingGroups() {
        var group = create("expiry").group();
        Long id = groups.findByGroupNumberAndMemberId(group.groupNumber(), MEMBER_A).orElseThrow().getId();
        assertThat(service.expire(id)).isFalse();
        jdbc.update("UPDATE payment_group SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(60)), id);
        assertThat(service.expire(id)).isTrue();
        assertThat(service.expire(id)).isFalse();
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.EXPIRED);
        stock(salesA, 10, 0); stock(salesB, 10, 0);
        var confirming = create("confirming").group();
        var attempt = service.start(MEMBER_A, confirming.groupNumber(), "pay");
        jdbc.update("UPDATE payment_group SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(60)), attempt.getPaymentGroupId());
        assertThat(service.expire(attempt.getPaymentGroupId())).isFalse();
        stock(salesA, 8, 2); stock(salesB, 9, 1);
    }

    @Test
    void onePaymentSuccessConsumesStockAndDeletesOnlyUneditedSelectedCartRows() {
        var group = create("success").group();
        cart.changeQuantity(MEMBER_A, a.getId(), 3);
        var first = service.start(MEMBER_A, group.groupNumber(), "payment-key");
        var replay = service.start(MEMBER_A, group.groupNumber(), "payment-key");
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(payments.count()).isEqualTo(1);
        verify(engine, times(1)).schedule(first.getId(), PaymentScenario.INSTANT_SUCCESS);
        assertThatThrownBy(() -> service.start(MEMBER_A, group.groupNumber(), "other-key")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.cancel(MEMBER_A, group.groupNumber())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.payment(MEMBER_B, group.groupNumber(), first.getId())).isInstanceOf(BusinessException.class);
        assertThat(service.resolve(first.getId())).isTrue();
        assertThat(service.resolve(first.getId())).isFalse();
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.PAID);
        assertThat(items.findAll()).extracting(CartItem::getId).containsExactlyInAnyOrder(a.getId(), unselected.getId());
        assertThat(items.findById(a.getId()).orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(orders.findAll()).extracting(order -> order.getQuantity()).containsExactlyInAnyOrder(2, 1);
        stock(salesA, 8, 0); stock(salesB, 9, 0);
    }

    @Test
    void failedPaymentReturnsAllStockLeavesCartAndAllowsNewGroup() {
        var group = create("failure").group();
        scenarios.set(group.groupNumber(), PaymentScenario.INSTANT_FAIL);
        var attempt = service.start(MEMBER_A, group.groupNumber(), "payment-key");
        assertThat(service.resolve(attempt.getId())).isTrue();
        assertThat(service.resolve(attempt.getId())).isFalse();
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.FAILED);
        assertThat(payments.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.FAILED);
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 10, 0); stock(salesB, 10, 0);
        assertThat(create("new-group").created()).isTrue();
    }

    @Test
    void gatewayApprovalSurvivesLocalApplyRollbackAndRetryUsesOneRecordedApproval() {
        var group = create("fault").group();
        var attempt = service.start(MEMBER_A, group.groupNumber(), "payment-key");
        // A is applied first; corrupt B so the transaction fails after A's consumption/deletion.
        jdbc.update("UPDATE sales_stock SET reserved=0 WHERE sales_info_id=?", salesB);
        assertThatThrownBy(() -> service.resolve(attempt.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result WHERE attempt_id=?", Integer.class, attempt.getId())).isEqualTo(1);
        Instant approval = jdbc.queryForObject("SELECT authorized_at FROM mock_gateway_result WHERE attempt_id=?", Timestamp.class, attempt.getId()).toInstant();
        assertThat(jdbc.queryForObject("SELECT outcome FROM mock_gateway_result WHERE attempt_id=?", String.class, attempt.getId())).isEqualTo("SUCCESS");
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.PAYMENT_CONFIRMING);
        assertThat(payments.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(items.count()).isEqualTo(3);
        stock(salesA, 8, 2);
        jdbc.update("UPDATE sales_stock SET reserved=1 WHERE sales_info_id=?", salesB);
        assertThat(service.resolve(attempt.getId())).isTrue();
        assertThat(service.resolve(attempt.getId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result WHERE attempt_id=?", Integer.class, attempt.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT authorized_at FROM mock_gateway_result WHERE attempt_id=?", Timestamp.class, attempt.getId()).toInstant()).isEqualTo(approval);
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(items.findAll()).extracting(CartItem::getId).containsExactly(unselected.getId());
        stock(salesA, 8, 0); stock(salesB, 9, 0);
    }

    @Test
    void concurrentSameKeyCreatesOneGroupAndReservesOnce() throws Exception {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var numbers = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) pool.submit(() -> {
                try { start.await(); numbers.add(create("concurrent").group().groupNumber()); }
                catch (Throwable failure) { failures.add(failure); }
                finally { done.countDown(); }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).isEmpty();
        assertThat(numbers).hasSize(4);
        assertThat(numbers.stream().distinct().count()).isEqualTo(1);
        assertThat(groups.count()).isEqualTo(1); assertThat(orders.count()).isEqualTo(2);
        stock(salesA, 8, 2); stock(salesB, 9, 1);
    }

    @Test
    void concurrentSingleAndGroupCreationShareOneDatabasePurchaseGuard() throws Exception {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var accepted = new ConcurrentLinkedQueue<String>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> {
                try { start.await(); accepted.add(create("race-group").group().groupNumber()); }
                catch (Throwable failure) { failures.add(failure); }
                finally { done.countDown(); }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    accepted.add(singleCreation.create(new CreateOrderCommand(1L, 1, "회원", "01012345678", MEMBER_A, 10000L), "race-single").order().getOrderNumber());
                } catch (Throwable failure) { failures.add(failure); }
                finally { done.countDown(); }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(accepted).hasSize(1);
        assertThat(failures).hasSize(1).allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class));
        assertThat(groups.count()).isEqualTo(1);
        var winner = service.active(MEMBER_A).orElseThrow();
        if (winner.totalAmount() == 25000) {
            assertThat(orders.count()).isEqualTo(2);
            stock(salesA, 8, 2); stock(salesB, 9, 1);
        } else {
            assertThat(winner.totalAmount()).isEqualTo(10000);
            assertThat(orders.count()).isEqualTo(1);
            stock(salesA, 9, 1); stock(salesB, 10, 0);
        }
    }

    @Test
    void concurrentStartAndCancelCommitExactlyOneTransition() throws Exception {
        var group = create("start-cancel").group();
        var accepted = new ConcurrentLinkedQueue<String>();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> {
                try {
                    ready.countDown(); start.await();
                    service.start(MEMBER_A, group.groupNumber(), "payment-race");
                    accepted.add("start");
                } catch (Throwable failure) { failures.add(failure); }
                finally { done.countDown(); }
            });
            pool.submit(() -> {
                try {
                    ready.countDown(); start.await();
                    service.cancel(MEMBER_A, group.groupNumber());
                    accepted.add("cancel");
                } catch (Throwable failure) { failures.add(failure); }
                finally { done.countDown(); }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(accepted).hasSize(1);
        assertThat(failures).hasSize(1).allSatisfy(failure -> {
            assertThat(failure).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        });
        assertThat(items.count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result", Integer.class)).isZero();
        if (accepted.contains("start")) {
            assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.PAYMENT_CONFIRMING);
            assertThat(payments.count()).isEqualTo(1);
            var attempt = payments.findAll().getFirst();
            assertThat(attempt.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            verify(engine, times(1)).schedule(attempt.getId(), PaymentScenario.INSTANT_SUCCESS);
            stock(salesA, 8, 2); stock(salesB, 9, 1);
        } else {
            assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.CANCELLED);
            assertThat(payments.count()).isZero();
            stock(salesA, 10, 0); stock(salesB, 10, 0);
            service.cancel(MEMBER_A, group.groupNumber());
            stock(salesA, 10, 0); stock(salesB, 10, 0);
        }
    }

    @Test
    void concurrentResolveRecordsOneApprovalAndConsumesEveryProductOnce() throws Exception {
        var group = create("resolve-race").group();
        var attempt = service.start(MEMBER_A, group.groupNumber(), "payment-race");
        var results = new ConcurrentLinkedQueue<Boolean>();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) pool.submit(() -> {
                try {
                    ready.countDown(); start.await();
                    results.add(service.resolve(attempt.getId()));
                } catch (Throwable failure) { failures.add(failure); }
                finally { done.countDown(); }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).isEmpty();
        assertThat(results).containsExactlyInAnyOrder(true, false, false, false);
        assertThat(service.get(MEMBER_A, group.groupNumber()).status()).isEqualTo(OrderStatus.PAID);
        assertThat(orders.findAll()).extracting(order -> order.getStatus()).containsOnly(OrderStatus.PAID);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(payments.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result WHERE attempt_id=?", Integer.class, attempt.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM mock_gateway_result WHERE attempt_id=?", String.class, attempt.getId())).isEqualTo("SUCCESS");
        assertThat(items.findAll()).extracting(CartItem::getId).containsExactly(unselected.getId());
        stock(salesA, 8, 0); stock(salesB, 9, 0);
        assertThat(service.resolve(attempt.getId())).isFalse();
        stock(salesA, 8, 0); stock(salesB, 9, 0);
    }

}
