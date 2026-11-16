package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.cart.service.CartService;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.order.service.PaymentGroupService;
import com.shoppinglive.commercecore.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentScenario;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.payment.service.DurableMockGateway;
import com.shoppinglive.payment.service.MockPaymentEngine;
import com.shoppinglive.payment.service.PaymentRecoveryStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class PaymentRecoveryPostgresTest extends PaymentDatabaseTestSupport {
    @Autowired com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore deliveries;
    @Autowired com.shoppinglive.commercecore.payment.service.PaymentDispatcher dispatcher;
    @Autowired PaymentService service;
    @Autowired PaymentGroupService groups;
    @Autowired PaymentGroupRepository groupRows;
    @Autowired PaymentAttemptJpaRepository attempts;
    @Autowired CartService cart;
    @Autowired CartItemRepository carts;
    @Autowired OrderJpaRepository orders;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @Autowired InMemoryProductLookupStub productLookup;
    @Autowired DevPaymentScenarioRegistry scenarios;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentClientTestConfiguration.BoundaryPaymentServer paymentServer;
    @Autowired MockPaymentEngine engine;
    @Autowired DurableMockGateway gateway;
    @Autowired PaymentRecoveryStore recovery;
    static final String COUPON = "aaaaaaaa-1111-4111-8111-111111111111";
    long stockId;

    @BeforeEach
    void seed() {
        cleanup();
        productLookup.register(new ProductSnapshot(99001L, "상품", null, SELLER));
        stockId = sales.saveAndFlush(new Sales(99001L, 10000L, SalesStatus.ON_SALE)).getId();
        stocks.saveAndFlush(new SalesStock(stockId, 5, 0));
        prepareLegacyTradeFixtures();
        jdbc.update(
                "INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,starts_at,ends_at,expires_at,created_at) VALUES(?,?,'쿠폰',1000,1,1,CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP+INTERVAL '2 hours',CURRENT_TIMESTAMP)",
                COUPON,
                SELLER);
        jdbc.update("INSERT INTO coupon_target(coupon_id,product_id) VALUES(?,99001)", COUPON);
        jdbc.update(
                "INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES(?,?,?,'AVAILABLE',CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),
                COUPON,
                MEMBER_A);
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null) {
            return;
        }
        paymentServer.jdbc.update("DELETE FROM event_outbox");
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        attempts.deleteAll();
        jdbc.update("DELETE FROM payment_delivery_request");
        orders.deleteAll();
        groupRows.deleteAll();
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", COUPON);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", COUPON);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", COUPON);
        carts.deleteAll();
        stocks.deleteAll();
        sales.deleteAll();
        jdbc.update("DELETE FROM member_purchase_guard");
        productLookup.clear();
    }

    PaymentExecution start(PaymentScenario scenario) {
        var item = cart.add(MEMBER_A, 99001L, 1);
        var group =
                groups.create(
                                MEMBER_A,
                                List.of(
                                        new PaymentGroupService.Selection(
                                                item.getId(), item.getVersion())),
                                "회원",
                                "010",
                                10000,
                                COUPON,
                                "recovery-order")
                        .group();
        scenarios.set(group.groupNumber(), scenario);
        return groups.start(MEMBER_A, group.groupNumber(), "recovery-payment");
    }

    void due(long id) {
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET scheduled_resolve_at=clock_timestamp() WHERE id=?", id);
    }

    String coupon() {
        return jdbc.queryForObject(
                "SELECT status FROM member_coupon WHERE coupon_id=?", String.class, COUPON);
    }

    void held(long id) {
        assertThat(attempts.findById(id).orElseThrow().getResolvedAt()).isNull();
        assertThat(orders.findAll())
                .extracting(o -> o.getStatus().name())
                .containsOnly("PAYMENT_CONFIRMING");
        assertThat(coupon()).isEqualTo("RESERVED");
        assertThat(legacyStock(stockId).getReserved()).isEqualTo(1);
    }

    @DisplayName("승인 응답이 유실되면 예약을 유지하고 재승인 없이 기존 승인을 반영한다")
    @Test
    void lostSuccessResponseKeepsReservationsAndExistingApprovalAppliesWithoutRetry() {
        var p = start(PaymentScenario.SUCCESS_RESPONSE_LOST);
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
        held(p.getId());
        assertThat(attempts.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.UNKNOWN);
        due(p.getId());
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isTrue();
        assertThat(coupon()).isEqualTo("USED");
        assertThat(legacyStock(stockId).getReserved()).isZero();
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT retry_count FROM payment_attempt WHERE id=?",
                                Integer.class,
                                p.getId()))
                .isZero();
        verify(gateway, times(1))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
    }

    @DisplayName("실패 응답이 유실되면 확정 실패를 조회할 때까지 예약을 해제하지 않는다")
    @Test
    void lostFailureResponseDoesNotReleaseUntilAuthoritativeFailureIsRead() {
        var p = start(PaymentScenario.FAILURE_RESPONSE_LOST);
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
        held(p.getId());
        due(p.getId());
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isTrue();
        assertThat(coupon()).isEqualTo("AVAILABLE");
        assertThat(legacyStock(stockId).getAvailable()).isEqualTo(5);
        assertThat(legacyStock(stockId).getReserved()).isZero();
        assertThat(attempts.findById(p.getId()).orElseThrow().getStatus())
                .isEqualTo(com.shoppinglive.payment.entity.PaymentStatus.FAILED);
    }

    @DisplayName("실행 네 번이 실패하면 한도를 소진하고 저장소 재생성 후에는 조회만 수행한다")
    @Test
    void fourFailedInvocationsExhaustBudgetAndRecreatedStoreOnlyQueries() {
        var p = start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        for (int i = 0; i < 4; i++) {
            due(p.getId());
            assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
            held(p.getId());
            assertThat(
                            paymentServer.jdbc.queryForObject(
                                    "SELECT retry_count FROM payment_attempt WHERE id=?",
                                    Integer.class,
                                    p.getId()))
                    .isEqualTo(i);
            double delta =
                    paymentServer.jdbc.queryForObject(
                            "SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",
                            Double.class,
                            p.getId());
            assertThat(delta).isBetween(-1.0, i == 3 ? 60.0 : (double) (1 << i));
        }
        verify(gateway, times(4))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        due(p.getId());
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
        held(p.getId());
        verify(gateway, times(4))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_gateway_result", Integer.class))
                .isZero();
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",
                                Double.class,
                                p.getId()))
                .isBetween(55.0, 60.0);
    }

    @DisplayName("한도 소진 후 조회에 실패해도 1분 간격을 유지하고 새 실행을 하지 않는다")
    @Test
    void queryFailureAtExhaustionKeepsOneMinuteIntervalAndDoesNotExecute() {
        var p = start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET status='UNKNOWN',execution_started_at=requested_at,retry_count=3 WHERE id=?",
                p.getId());
        doThrow(new IllegalStateException("결제 제공자 결과 조회를 사용할 수 없습니다."))
                .when(gateway)
                .find(p.getId());
        assertThatThrownBy(() -> dispatcher.dispatch(deliveries.byPayment(p.getId())))
                .isInstanceOf(IllegalStateException.class);
        held(p.getId());
        verify(gateway, never())
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",
                                Double.class,
                                p.getId()))
                .isBetween(55.0, 60.0);
    }

    @DisplayName("업무 반영이 롤백되어도 승인을 보존하고 실행 횟수를 추가 소비하지 않는다")
    @Test
    void approvalSurvivesBusinessRollbackAndDoesNotConsumeAnotherInvocation() {
        var p = start(PaymentScenario.INSTANT_SUCCESS);
        jdbc.update("UPDATE sku SET reserved=0 WHERE id=?", stockId);
        assertThatThrownBy(() -> dispatcher.dispatch(deliveries.byPayment(p.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(coupon()).isEqualTo("RESERVED");
        assertThat(orders.findAll())
                .extracting(o -> o.getStatus().name())
                .containsOnly("PAYMENT_CONFIRMING");
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_gateway_result", Integer.class))
                .isEqualTo(1);
        jdbc.update("UPDATE sku SET reserved=1 WHERE id=?", stockId);
        due(p.getId());
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isTrue();
        verify(gateway, times(1))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT retry_count FROM payment_attempt WHERE id=?",
                                Integer.class,
                                p.getId()))
                .isZero();
        assertThat(coupon()).isEqualTo("USED");
    }

    @DisplayName("최종 저장 시 작업 토큰이 만료되면 주문·재고·쿠폰 반영을 롤백하고 승인은 보존한다")
    @Test
    void tokenExpiryAtFinalWriteRollsBackOrderStockCouponAndRetainsApproval() {
        var p = start(PaymentScenario.INSTANT_SUCCESS);
        doAnswer(
                        inv -> {
                            paymentServer.jdbc.update(
                                    "UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                                    p.getId());
                            return inv.callRealMethod();
                        })
                .when(recovery)
                .complete(any(), eq("SUCCESS"));
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='PaymentExecutionResolved'",
                                Long.class))
                .isZero();
        held(p.getId());
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_gateway_result", Integer.class))
                .isEqualTo(1);
        doCallRealMethod().when(recovery).complete(any(), eq("SUCCESS"));
        due(p.getId());
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isTrue();
        verify(gateway, times(1))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
    }

    @DisplayName("실행 중단은 제공자 호출 전에 실행 예산 한 번을 소비한다")
    @Test
    void interruptedInvocationConsumesOneBudgetSlotBeforeProviderIsCalled() {
        var p = start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        var lease = recovery.claim(p.getId(), Duration.ofMinutes(1)).orElseThrow();
        assertThat(recovery.beginInvocation(lease)).contains(0);
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                p.getId());
        assertThat(dispatcher.dispatch(deliveries.byPayment(p.getId()))).isFalse();
        held(p.getId());
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT retry_count FROM payment_attempt WHERE id=?",
                                Integer.class,
                                p.getId()))
                .isEqualTo(1);
        verify(gateway, times(1))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
    }

    @Autowired PaymentBoundaryReconciler scanner;

    @DisplayName("스캐너는 만료된 처리 임대를 복구하고 저장된 승인을 한 번만 반영한다")
    @Test
    void scannerRecoversExpiredProcessingLeaseAndAppliesExistingApprovalOnlyOnce() {
        var p = start(PaymentScenario.INSTANT_SUCCESS);
        var lease = recovery.claim(p.getId(), Duration.ofMinutes(1)).orElseThrow();
        assertThat(recovery.beginInvocation(lease)).contains(0);
        gateway.authorize(
                p.getId(),
                com.shoppinglive.payment.entity.PaymentScenario.valueOf(p.getScenario().name()));
        assertThat(scanner.reconcileOverdue()).isZero();
        held(p.getId());
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?",
                p.getId());
        assertThat(scanner.reconcileOverdue()).isEqualTo(1);
        assertThat(scanner.reconcileOverdue()).isZero();
        verify(gateway, times(1))
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        assertThat(coupon()).isEqualTo("USED");
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT retry_count FROM payment_attempt WHERE id=?",
                                Integer.class,
                                p.getId()))
                .isZero();
    }

    @DisplayName("한도 소진 후 스캐너는 새 승인 없이 조회하고 다음 실행을 1분 뒤로 예약한다")
    @Test
    void scannerAtExhaustionQueriesWithoutNewAuthorizationAndSchedulesOneMinute() {
        var p = start(PaymentScenario.UNAVAILABLE_BEFORE_RESULT);
        paymentServer.jdbc.update(
                "UPDATE payment_attempt SET status='UNKNOWN',execution_started_at=requested_at,retry_count=3 WHERE id=?",
                p.getId());
        assertThat(scanner.reconcileOverdue()).isZero();
        held(p.getId());
        verify(gateway, never())
                .authorize(
                        p.getId(),
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(
                                p.getScenario().name()));
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM scheduled_resolve_at-clock_timestamp()) FROM payment_attempt WHERE id=?",
                                Double.class,
                                p.getId()))
                .isBetween(55.0, 60.0);
        assertThat(scanner.reconcileOverdue()).isZero();
    }
}
