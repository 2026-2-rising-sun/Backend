package com.shoppinglive.commerce.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.shoppinglive.commerce.cart.application.CartService;
import com.shoppinglive.commerce.orders.application.CreateOrderCommand;
import com.shoppinglive.commerce.orders.application.OrderCreationService;
import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.payments.application.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.payments.application.MockPaymentEngine;
import com.shoppinglive.commerce.payments.application.PaymentService;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService;
import com.shoppinglive.commerce.sales.infrastructure.SalesInfoEventProducer;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.contracts.events.SalesInfoEvent;
import com.shoppinglive.contracts.events.SalesInfoEventType;
import com.shoppinglive.contracts.events.SalesInfoStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
class SalesInfoEventIntegrationTest extends CommerceSecurityTestSupport {
    @Autowired SalesRegistrationService registration;
    @Autowired SalesService sales;
    @Autowired SalesInfoEventTracker tracker;
    @Autowired OrderCreationService creation;
    @Autowired OrderService orders;
    @Autowired PaymentGroupService groups;
    @Autowired PaymentService payments;
    @Autowired DevPaymentScenarioRegistry scenarios;
    @Autowired CartService cart;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean SalesInfoEventProducer producer;
    @MockitoBean MockPaymentEngine engine;
    final List<SalesInfoEvent> emitted = new CopyOnWriteArrayList<>();
    final List<Long> fixtureSales = new java.util.ArrayList<>();

    @BeforeEach
    void capture() {
        emitted.clear();
        doAnswer(call -> { emitted.add(call.getArgument(0)); return null; }).when(producer).send(any());
    }

    private long register(int available) {
        long product = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 1);
        shopping.register(new ProductSnapshot(product, "판매정보 테스트", null));
        long id = registration.register(product, 1000L, available).getId();
        fixtureSales.add(id);
        return id;
    }

    @AfterEach
    void clean() {
        for (long id : fixtureSales) {
            jdbc.queryForList("SELECT order_number FROM orders WHERE sales_info_id=?", String.class, id)
                .forEach(scenarios::clear);
            jdbc.update("DELETE FROM mock_gateway_result WHERE attempt_id IN (SELECT p.id FROM payment_attempt p JOIN orders o ON o.id=p.order_id WHERE o.sales_info_id=?)", id);
            jdbc.update("DELETE FROM payment_attempt WHERE order_id IN (SELECT id FROM orders WHERE sales_info_id=?)", id);
            jdbc.update("DELETE FROM orders WHERE sales_info_id=?", id);
            jdbc.update("DELETE FROM cart_item WHERE product_id=(SELECT product_id FROM sales_info WHERE id=?)", id);
            jdbc.update("DELETE FROM sales_stock WHERE sales_info_id=?", id);
            jdbc.update("DELETE FROM sales_info WHERE id=?", id);
        }
    }

    private long product(long id) { return jdbc.queryForObject("SELECT product_id FROM sales_info WHERE id=?", Long.class, id); }
    private long version(long id) { return jdbc.queryForObject("SELECT snapshot_version FROM sales_info WHERE id=?", Long.class, id); }
    private String member() { return UUID.randomUUID().toString(); }

    @Test
    void capturesFourTypesAndOneFinalSnapshotPerTransaction() {
        long id = register(3);
        assertThat(emitted).extracting(SalesInfoEvent::eventType).containsExactly(SalesInfoEventType.SalesRegistered);
        assertThat(emitted.getFirst().payload().snapshotVersion()).isEqualTo(1);
        emitted.clear();
        tx.executeWithoutResult(status -> {
            sales.changePrice(id, 2000L);
            sales.adjustAvailable(id, 2);
            sales.changeStatus(id, SalesStatus.ON_SALE);
            assertThat(emitted).isEmpty();
        });
        assertThat(emitted).extracting(SalesInfoEvent::eventType).containsExactly(
            SalesInfoEventType.PriceChanged, SalesInfoEventType.InventoryChanged, SalesInfoEventType.SalesStatusChanged);
        assertThat(emitted).allSatisfy(event -> {
            assertThat(event.payload()).isEqualTo(emitted.getFirst().payload());
            assertThat(event.payload().snapshotVersion()).isEqualTo(2);
            assertThat(event.payload().price()).isEqualTo(2000);
            assertThat(event.payload().available()).isEqualTo(5);
            assertThat(event.payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
        });
        assertThat(emitted).extracting(SalesInfoEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    void rollbackNoopAndFailedMutationDoNotPublishOrAdvanceRevision() {
        long id = register(3);
        emitted.clear();
        tx.executeWithoutResult(status -> { sales.changePrice(id, 5000L); status.setRollbackOnly(); });
        sales.changePrice(id, 1000L);
        sales.adjustAvailable(id, 0);
        assertThatThrownBy(() -> sales.adjustAvailable(id, -4)).isInstanceOf(InsufficientStockException.class);
        assertThat(emitted).isEmpty();
        assertThat(version(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT price FROM sales_info WHERE id=?", Long.class, id)).isEqualTo(1000);
    }

    @Test
    void registrationRollbackDoesNotPublish() {
        tx.executeWithoutResult(status -> { register(2); status.setRollbackOnly(); });
        assertThat(emitted).isEmpty();
    }

    @Test
    void orderReservationSoldOutCancellationAndReplayPublishOnlyRealChanges() {
        long id = register(1);
        sales.changeStatus(id, SalesStatus.ON_SALE);
        emitted.clear();
        String member = member();
        var command = new CreateOrderCommand(product(id), 1, "구매자", "01012345678", member, 1000L);
        var order = creation.create(command, "replay").order();
        assertThat(emitted).extracting(SalesInfoEvent::eventType).containsExactly(
            SalesInfoEventType.InventoryChanged, SalesInfoEventType.SalesStatusChanged);
        assertThat(emitted).allSatisfy(e -> assertThat(e.payload().snapshotVersion()).isEqualTo(3));
        emitted.clear();
        creation.create(command, "replay");
        assertThat(emitted).isEmpty();
        orders.cancelBeforePayment(order.getOrderNumber(), member);
        assertThat(emitted).extracting(SalesInfoEvent::eventType).containsExactly(
            SalesInfoEventType.InventoryChanged, SalesInfoEventType.SalesStatusChanged);
        assertThat(emitted).allSatisfy(e -> {
            assertThat(e.payload().available()).isEqualTo(1);
            assertThat(e.payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
            assertThat(e.payload().snapshotVersion()).isEqualTo(4);
        });
    }

    @Test
    void failedPaymentRestoresStockButSuccessfulPaymentDoesNotChangePayload() {
        for (PaymentScenario scenario : List.of(PaymentScenario.INSTANT_FAIL, PaymentScenario.INSTANT_SUCCESS)) {
            long id = register(2);
            sales.changeStatus(id, SalesStatus.ON_SALE);
            String member = member();
            var order = creation.create(new CreateOrderCommand(product(id), 1, "구매자", "01012345678", member, 1000L), "payment").order();
            scenarios.set(order.getOrderNumber(), scenario);
            emitted.clear();
            var attempt = payments.startPayment(order.getOrderNumber(), member);
            payments.resolvePayment(attempt.getId());
            if (scenario == PaymentScenario.INSTANT_FAIL) {
                assertThat(emitted).extracting(SalesInfoEvent::eventType).containsExactly(SalesInfoEventType.InventoryChanged);
                assertThat(emitted.getFirst().payload().available()).isEqualTo(2);
                assertThat(version(id)).isEqualTo(4);
            } else {
                assertThat(emitted).isEmpty();
                assertThat(version(id)).isEqualTo(3);
            }
        }
    }

    @Test
    void multiProductReservationAndExpiryCaptureEachSale() {
        long a = register(1), b = register(1);
        sales.changeStatus(a, SalesStatus.ON_SALE);
        sales.changeStatus(b, SalesStatus.ON_SALE);
        String member = member();
        var ca = cart.add(member, product(a), 1);
        var cb = cart.add(member, product(b), 1);
        emitted.clear();
        var group = groups.create(member, List.of(new PaymentGroupService.Selection(cb.getId(), cb.getVersion()),
            new PaymentGroupService.Selection(ca.getId(), ca.getVersion())), "구매자", "01012345678", 2000, "group").group();
        assertThat(emitted).hasSize(4);
        assertThat(emitted).allSatisfy(e -> {
            assertThat(e.payload().snapshotVersion()).isEqualTo(3);
            assertThat(e.payload().available()).isZero();
        });
        Long groupId = jdbc.queryForObject("SELECT id FROM payment_group WHERE group_number=?", Long.class, group.groupNumber());
        jdbc.update("UPDATE payment_group SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(60)), groupId);
        emitted.clear();
        assertThat(groups.expire(groupId)).isTrue();
        assertThat(emitted).hasSize(4);
        assertThat(emitted).allSatisfy(e -> {
            assertThat(e.payload().snapshotVersion()).isEqualTo(4);
            assertThat(e.payload().available()).isEqualTo(1);
            assertThat(e.payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
        });
    }

    @Test
    void concurrentPriceAndStockChangesHaveConsistentMonotonicSnapshots() throws Exception {
        long id = register(10);
        emitted.clear();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var price = pool.submit(() -> { start.await(); sales.changePrice(id, 2000L); return null; });
            var stock = pool.submit(() -> { start.await(); sales.adjustAvailable(id, -1); return null; });
            start.countDown(); price.get(10, TimeUnit.SECONDS); stock.get(10, TimeUnit.SECONDS);
        }
        var ordered = emitted.stream().sorted(java.util.Comparator.comparingLong(e -> e.payload().snapshotVersion())).toList();
        assertThat(ordered).hasSize(2);
        assertThat(ordered).extracting(e -> e.payload().snapshotVersion()).containsExactly(2L, 3L);
        assertThat(ordered.getLast().payload().price()).isEqualTo(2000);
        assertThat(ordered.getLast().payload().available()).isEqualTo(9);
        assertThat(version(id)).isEqualTo(3);
    }

    @Test
    void requiresNewCollectionIsIndependentFromOuterRollback() {
        long outerId = register(1), innerId = register(1);
        emitted.clear();
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            sales.changePrice(outerId, 2000L);
            inner.executeWithoutResult(ignored -> sales.adjustAvailable(innerId, 1));
            status.setRollbackOnly();
        });
        assertThat(emitted).hasSize(1);
        assertThat(emitted.getFirst().payload().salesId()).isEqualTo(innerId);
        assertThat(version(outerId)).isEqualTo(1);
        assertThat(version(innerId)).isEqualTo(2);
    }

    @Test
    void trackerRejectsNonTransactionalCallsAndMissingSaleRemainsNotFound() {
        assertThatThrownBy(() -> tracker.watch(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sales.changePrice(Long.MAX_VALUE, 1000L)).isInstanceOf(SalesNotFoundException.class);
    }
}
