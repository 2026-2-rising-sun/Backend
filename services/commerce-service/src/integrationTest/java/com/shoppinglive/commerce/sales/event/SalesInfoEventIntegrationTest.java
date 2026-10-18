package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.service.CartService;
import com.shoppinglive.commerce.order.service.OrderCreationService;
import com.shoppinglive.commerce.order.service.OrderService;
import com.shoppinglive.commerce.order.service.PaymentGroupService;
import com.shoppinglive.commerce.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.service.MockPaymentEngine;
import com.shoppinglive.commerce.payment.service.PaymentService;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.exception.InsufficientStockException;
import com.shoppinglive.commerce.sales.exception.SalesNotFoundException;
import com.shoppinglive.commerce.sales.service.SalesRegistrationService;
import com.shoppinglive.commerce.sales.service.SalesService;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
    @MockitoSpyBean EventOutbox outbox;
    @MockitoBean MockPaymentEngine engine;
    final List<EventEnvelope<SalesInfoSnapshot>> emitted = new CopyOnWriteArrayList<>();
    final List<Long> fixtureSales = new java.util.ArrayList<>();

    @BeforeEach
    void capture() {
        emitted.clear();
        doAnswer(
                        call -> {
                            var result = call.callRealMethod();
                            emitted.add(call.getArgument(1));
                            return result;
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<EventOutbox>getUltimateTargetObject(outbox))
                .append(eq(SalesInfoEventContract.TOPIC_NAME), any());
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
            jdbc.queryForList(
                            "SELECT order_number FROM orders WHERE sales_info_id=?",
                            String.class,
                            id)
                    .forEach(scenarios::clear);
            jdbc.update(
                    "DELETE FROM mock_gateway_result WHERE attempt_id IN (SELECT p.id FROM payment_attempt p JOIN orders o ON o.id=p.order_id WHERE o.sales_info_id=?)",
                    id);
            jdbc.update(
                    "DELETE FROM payment_attempt WHERE order_id IN (SELECT id FROM orders WHERE sales_info_id=?)",
                    id);
            jdbc.update("DELETE FROM orders WHERE sales_info_id=?", id);
            jdbc.update(
                    "DELETE FROM cart_item WHERE product_id=(SELECT product_id FROM sales_info WHERE id=?)",
                    id);
            jdbc.update("DELETE FROM sales_stock WHERE sales_info_id=?", id);
            jdbc.update("DELETE FROM sales_info WHERE id=?", id);
        }
    }

    private long product(long id) {
        return jdbc.queryForObject("SELECT product_id FROM sales_info WHERE id=?", Long.class, id);
    }

    private long version(long id) {
        return jdbc.queryForObject(
                "SELECT snapshot_version FROM sales_info WHERE id=?", Long.class, id);
    }

    private String member() {
        return UUID.randomUUID().toString();
    }

    @DisplayName("트랜잭션별 최종 스냅샷 하나에서 네 종류의 이벤트를 수집한다")
    @Test
    void capturesFourTypesAndOneFinalSnapshotPerTransaction() {
        long id = register(3);
        assertThat(emitted).extracting(EventEnvelope::eventType).containsExactly("SalesRegistered");
        assertThat(emitted.getFirst().payload().snapshotVersion()).isEqualTo(1);
        emitted.clear();
        tx.executeWithoutResult(
                status -> {
                    sales.changePrice(id, 2000L);
                    sales.adjustAvailable(id, 2);
                    sales.changeStatus(id, SalesStatus.ON_SALE);
                    assertThat(emitted).isEmpty();
                });
        assertThat(emitted)
                .extracting(EventEnvelope::eventType)
                .containsExactly("PriceChanged", "InventoryChanged", "SalesStatusChanged");
        assertThat(emitted)
                .allSatisfy(
                        event -> {
                            assertThat(event.payload()).isEqualTo(emitted.getFirst().payload());
                            assertThat(event.payload().snapshotVersion()).isEqualTo(2);
                            assertThat(event.payload().price()).isEqualTo(2000);
                            assertThat(event.payload().available()).isEqualTo(5);
                            assertThat(event.payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
                        });
        assertThat(emitted).extracting(EventEnvelope::eventId).doesNotHaveDuplicates();
    }

    @DisplayName("롤백·무변경·변경 실패 시 이벤트를 발행하거나 버전을 증가시키지 않는다")
    @Test
    void rollbackNoopAndFailedMutationDoNotPublishOrAdvanceRevision() {
        long id = register(3);
        emitted.clear();
        tx.executeWithoutResult(
                status -> {
                    sales.changePrice(id, 5000L);
                    status.setRollbackOnly();
                });
        sales.changePrice(id, 1000L);
        sales.adjustAvailable(id, 0);
        assertThatThrownBy(() -> sales.adjustAvailable(id, -4))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(emitted).isEmpty();
        assertThat(version(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT price FROM sales_info WHERE id=?", Long.class, id))
                .isEqualTo(1000);
    }

    @DisplayName("판매 등록이 롤백되면 이벤트를 발행하지 않는다")
    @Test
    void registrationRollbackDoesNotPublish() {
        tx.executeWithoutResult(
                status -> {
                    register(2);
                    status.setRollbackOnly();
                });
        assertThat(emitted).isEmpty();
    }

    @DisplayName("주문 예약·품절·취소·재요청은 실제 변경이 있을 때만 이벤트를 발행한다")
    @Test
    void orderReservationSoldOutCancellationAndReplayPublishOnlyRealChanges() {
        long id = register(1);
        sales.changeStatus(id, SalesStatus.ON_SALE);
        emitted.clear();
        String member = member();
        var command = new CreateOrderCommand(product(id), 1, "구매자", "01012345678", member, 1000L);
        var order = creation.create(command, "replay").order();
        assertThat(emitted)
                .extracting(EventEnvelope::eventType)
                .containsExactly("InventoryChanged", "SalesStatusChanged");
        assertThat(emitted).allSatisfy(e -> assertThat(e.payload().snapshotVersion()).isEqualTo(3));
        emitted.clear();
        creation.create(command, "replay");
        assertThat(emitted).isEmpty();
        orders.cancelBeforePayment(order.getOrderNumber(), member);
        assertThat(emitted)
                .extracting(EventEnvelope::eventType)
                .containsExactly("InventoryChanged", "SalesStatusChanged");
        assertThat(emitted)
                .allSatisfy(
                        e -> {
                            assertThat(e.payload().available()).isEqualTo(1);
                            assertThat(e.payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
                            assertThat(e.payload().snapshotVersion()).isEqualTo(4);
                        });
    }

    @DisplayName("결제 실패의 재고 복구는 이벤트에 반영하고 결제 성공은 payload를 변경하지 않는다")
    @Test
    void failedPaymentRestoresStockButSuccessfulPaymentDoesNotChangePayload() {
        for (PaymentScenario scenario :
                List.of(PaymentScenario.INSTANT_FAIL, PaymentScenario.INSTANT_SUCCESS)) {
            long id = register(2);
            sales.changeStatus(id, SalesStatus.ON_SALE);
            String member = member();
            var order =
                    creation.create(
                                    new CreateOrderCommand(
                                            product(id), 1, "구매자", "01012345678", member, 1000L),
                                    "payment")
                            .order();
            scenarios.set(order.getOrderNumber(), scenario);
            emitted.clear();
            var attempt = payments.startPayment(order.getOrderNumber(), member);
            payments.resolvePayment(attempt.getId());
            if (scenario == PaymentScenario.INSTANT_FAIL) {
                assertThat(emitted)
                        .extracting(EventEnvelope::eventType)
                        .containsExactly("InventoryChanged");
                assertThat(emitted.getFirst().payload().available()).isEqualTo(2);
                assertThat(version(id)).isEqualTo(4);
            } else {
                assertThat(emitted).isEmpty();
                assertThat(version(id)).isEqualTo(3);
            }
        }
    }

    @DisplayName("여러 상품 예약과 만료 시 각 판매정보의 최종 스냅샷을 수집한다")
    @Test
    void multiProductReservationAndExpiryCaptureEachSale() {
        long a = register(1), b = register(1);
        sales.changeStatus(a, SalesStatus.ON_SALE);
        sales.changeStatus(b, SalesStatus.ON_SALE);
        String member = member();
        var ca = cart.add(member, product(a), 1);
        var cb = cart.add(member, product(b), 1);
        emitted.clear();
        var group =
                groups.create(
                                member,
                                List.of(
                                        new PaymentGroupService.Selection(
                                                cb.getId(), cb.getVersion()),
                                        new PaymentGroupService.Selection(
                                                ca.getId(), ca.getVersion())),
                                "구매자",
                                "01012345678",
                                2000,
                                "group")
                        .group();
        assertThat(emitted).hasSize(4);
        assertThat(emitted)
                .allSatisfy(
                        e -> {
                            assertThat(e.payload().snapshotVersion()).isEqualTo(3);
                            assertThat(e.payload().available()).isZero();
                        });
        Long groupId =
                jdbc.queryForObject(
                        "SELECT id FROM payment_group WHERE group_number=?",
                        Long.class,
                        group.groupNumber());
        jdbc.update(
                "UPDATE payment_group SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                groupId);
        emitted.clear();
        assertThat(groups.expire(groupId)).isTrue();
        assertThat(emitted).hasSize(4);
        assertThat(emitted)
                .allSatisfy(
                        e -> {
                            assertThat(e.payload().snapshotVersion()).isEqualTo(4);
                            assertThat(e.payload().available()).isEqualTo(1);
                            assertThat(e.payload().status()).isEqualTo(SalesInfoStatus.ON_SALE);
                        });
    }

    @DisplayName("가격과 재고를 동시에 변경해도 일관된 스냅샷 버전이 증가한다")
    @Test
    void concurrentPriceAndStockChangesHaveConsistentMonotonicSnapshots() throws Exception {
        long id = register(10);
        emitted.clear();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var price =
                    pool.submit(
                            () -> {
                                start.await();
                                sales.changePrice(id, 2000L);
                                return null;
                            });
            var stock =
                    pool.submit(
                            () -> {
                                start.await();
                                sales.adjustAvailable(id, -1);
                                return null;
                            });
            start.countDown();
            price.get(10, TimeUnit.SECONDS);
            stock.get(10, TimeUnit.SECONDS);
        }
        var ordered =
                emitted.stream()
                        .sorted(
                                java.util.Comparator.comparingLong(
                                        e -> e.payload().snapshotVersion()))
                        .toList();
        assertThat(ordered).hasSize(2);
        assertThat(ordered).extracting(e -> e.payload().snapshotVersion()).containsExactly(2L, 3L);
        assertThat(ordered.getLast().payload().price()).isEqualTo(2000);
        assertThat(ordered.getLast().payload().available()).isEqualTo(9);
        assertThat(version(id)).isEqualTo(3);
    }

    @DisplayName("REQUIRES_NEW의 이벤트 수집은 외부 트랜잭션 롤백과 독립적이다")
    @Test
    void requiresNewCollectionIsIndependentFromOuterRollback() {
        long outerId = register(1), innerId = register(1);
        emitted.clear();
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(
                status -> {
                    sales.changePrice(outerId, 2000L);
                    inner.executeWithoutResult(ignored -> sales.adjustAvailable(innerId, 1));
                    status.setRollbackOnly();
                });
        assertThat(emitted).hasSize(1);
        assertThat(emitted.getFirst().payload().salesId()).isEqualTo(innerId);
        assertThat(version(outerId)).isEqualTo(1);
        assertThat(version(innerId)).isEqualTo(2);
    }

    @DisplayName("트랜잭션 없는 수집을 거절하고 없는 판매정보는 기존 미존재 오류를 유지한다")
    @Test
    void trackerRejectsNonTransactionalCallsAndMissingSaleRemainsNotFound() {
        assertThatThrownBy(() -> tracker.watch(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sales.changePrice(Long.MAX_VALUE, 1000L))
                .isInstanceOf(SalesNotFoundException.class);
    }
}
