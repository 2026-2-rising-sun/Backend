package com.shoppinglive.commercecore.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.order.dto.CreateOrderCommand;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.service.OrderCreationService;
import com.shoppinglive.commercecore.order.service.OrderExpirationScheduler;
import com.shoppinglive.commercecore.order.service.OrderService;
import com.shoppinglive.commercecore.payment.entity.PaymentScenario;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.payment.service.PaymentService;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.InMemoryProductLookupStub;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commercecore.sku.service.SkuSalesService;
import com.shoppinglive.payment.repository.PaymentAttemptJpaRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class CommerceCoreStateTransitionTest
        extends com.shoppinglive.commercecore.support.CommerceCoreSecurityTestSupport {
    @Autowired com.shoppinglive.commercecore.payment.service.PaymentDispatcher dispatcher;
    @Autowired OrderCreationService creation;
    @Autowired OrderService orders;
    @Autowired OrderExpirationScheduler expiration;
    @Autowired com.shoppinglive.commercecore.order.service.PaymentGroupService groups;

    @Autowired
    com.shoppinglive.commercecore.order.service.PaymentGroupExpirationScheduler groupExpiration;

    @Autowired PaymentService payments;
    @Autowired com.shoppinglive.commercecore.payment.dto.DevPaymentScenarioRegistry scenarios;
    @Autowired com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore deliveries;
    @Autowired SkuSalesService sales;
    @Autowired OrderJpaRepository orderRepository;
    @Autowired PaymentAttemptJpaRepository paymentRepository;
    @Autowired SalesJpaRepository salesRepository;
    @Autowired SalesStockJpaRepository stockRepository;
    @Autowired InMemoryProductLookupStub productLookup;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;

    @Autowired
    com.shoppinglive.commercecore.payment.service.PaymentClientTestConfiguration
                    .BoundaryPaymentServer
            paymentServer;

    @Autowired MockMvc mvc;
    private Long salesId;

    @BeforeEach
    void setUp() {
        clean();
        productLookup.register(new ProductSnapshot(920L, "마지막 재고", null, SELLER, true));
        com.shoppinglive.commercecore.sku.SkuFixtures.product(jdbc, 920L, SELLER);
        jdbc.update("UPDATE product SET sale_enabled=TRUE WHERE id=920");
        salesId =
                com.shoppinglive.commercecore.sku.SkuFixtures.insert(
                        jdbc, 920, 10000, SalesStatus.ON_SALE, 1, 0);
    }

    @AfterEach
    void clean() {
        paymentServer.jdbc.update("DELETE FROM mock_gateway_result");
        paymentRepository.deleteAll();
        jdbc.update("DELETE FROM payment_delivery_request");
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        salesRepository.deleteAll();
        jdbc.update("DELETE FROM sku");
        jdbc.update("DELETE FROM product WHERE id=920");
        productLookup.clear();
    }

    private Order create() {
        return creation.create(
                        new CreateOrderCommand(
                                920L,
                                1,
                                "구매자",
                                "010-1234-5678",
                                "11111111-1111-4111-8111-111111111111",
                                10_000L),
                        null)
                .order();
    }

    private void release(Order order, String path) {
        switch (path) {
            case "cancel" ->
                    orders.cancelBeforePayment(
                            order.getOrderNumber(), "11111111-1111-4111-8111-111111111111");
            case "expire" -> {
                jdbc.update(
                        "UPDATE payment_group SET expires_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                        order.getPaymentGroup().getId());
                groupExpiration.run();
            }
            case "fail" -> {
                scenarios.set(order.getOrderNumber(), PaymentScenario.INSTANT_FAIL);
                var attempt = payments.startPayment(order.getOrderNumber(), MEMBER_A);
                assertThat(dispatcher.dispatch(deliveries.byPayment(attempt.getId()))).isTrue();
                assertThat(dispatcher.dispatch(deliveries.byPayment(attempt.getId()))).isFalse();
            }
            default -> throw new IllegalArgumentException(path);
        }
    }

    @DisplayName("마지막 예약 재고를 복구하면 다시 주문할 수 있다")
    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expire", "fail"})
    void allowsNewOrderAfterRestoringLastReservedItem(String path) {
        Order order = create();
        assertThat(legacySku(salesId).status()).isEqualTo(SalesStatus.SOLD_OUT);
        release(order, path);
        assertThat(legacyStock(salesId).getAvailable()).isEqualTo(1);
        assertThat(legacySku(salesId).status()).isEqualTo(SalesStatus.ON_SALE);
        assertThat(create().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @DisplayName("재고를 복구해도 판매자가 지정한 비공개 상태를 유지한다")
    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expire", "fail"})
    void preservesPrivateStatusWhenRestoringStock(String path) {
        Order order = create();
        sales.changeStatus(salesId, SalesStatus.PRIVATE, SELLER);
        release(order, path);
        assertThat(legacyStock(salesId).getAvailable()).isEqualTo(1);
        assertThat(legacySku(salesId).status()).isEqualTo(SalesStatus.PRIVATE);
    }

    @DisplayName("실제 스케줄 진입점에서 재고 복구에 실패하면 주문 만료도 롤백한다")
    @Test
    void rollsBackExpirationWhenScheduledStockRestoreFails() {
        Order order = create();
        jdbc.update(
                "UPDATE payment_group SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                order.getPaymentGroup().getId());
        jdbc.update("UPDATE sku SET reserved = 0 WHERE id = ?", salesId);
        assertThatThrownBy(() -> groups.expire(order.getPaymentGroup().getId()))
                .isInstanceOf(IllegalStateException.class);
        // 묶음 스케줄러는 실패한 묶음 하나를 분리해 로그로 남긴다.
        // 해당 트랜잭션은 롤백된 상태여야 한다.
        groupExpiration.run();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(legacyStock(salesId).getAvailable()).isZero();
    }

    @DisplayName("결제의 재고 처리에 실패하면 결제·주문 확정도 롤백한다")
    @ParameterizedTest
    @EnumSource(
            value = PaymentScenario.class,
            names = {"INSTANT_SUCCESS", "INSTANT_FAIL"})
    void rollsBackPaymentAndOrderConfirmationWhenStockUpdateFails(PaymentScenario scenario) {
        Order order = create();
        scenarios.set(order.getOrderNumber(), scenario);
        var attempt = payments.startPayment(order.getOrderNumber(), MEMBER_A);
        jdbc.update("UPDATE sku SET reserved = 0 WHERE id = ?", salesId);
        assertThatThrownBy(() -> dispatcher.dispatch(deliveries.byPayment(attempt.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(deliveries.payment(attempt.getId()).status())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(paymentRepository.findById(attempt.getId()).orElseThrow().getStatus().name())
                .isEqualTo(
                        com.shoppinglive.payment.entity.PaymentScenario.valueOf(scenario.name())
                                .getOutcome()
                                .name());
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
    }

    @DisplayName("0재고 SKU는 수동 상태를 ON_SALE로 저장하고 외부에는 SOLD_OUT을 반환한다")
    @ParameterizedTest
    @EnumSource(
            value = SalesStatus.class,
            names = {"READY", "ON_SALE", "SOLD_OUT", "PRIVATE"})
    void derivesSoldOutWhenStartingZeroStockSku(SalesStatus current) throws Exception {
        long skuId = salesId;
        jdbc.update(
                "UPDATE sku SET sale_status=?,available=0 WHERE id=?",
                current == SalesStatus.SOLD_OUT ? "ON_SALE" : current.name(),
                skuId);
        mvc.perform(
                        patch("/v1/sales/{id}/status", skuId)
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"ON_SALE\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                        "$.status")
                                .value("SOLD_OUT"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sale_status FROM sku WHERE id=?", String.class, skuId))
                .isEqualTo("ON_SALE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available FROM sku WHERE id=?", Integer.class, skuId))
                .isZero();
    }

    @DisplayName("판매자가 재고를 보충하면 품절만 해제하고 다른 상태는 유지한다")
    @Test
    void reopensOnlySoldOutSalesAfterStockReplenishment() {
        create();
        sales.adjustAvailable(salesId, 1, SELLER);
        assertThat(legacySku(salesId).status()).isEqualTo(SalesStatus.ON_SALE);
        sales.changeStatus(salesId, SalesStatus.PRIVATE, SELLER);
        sales.adjustAvailable(salesId, 1, SELLER);
        assertThat(legacySku(salesId).status()).isEqualTo(SalesStatus.PRIVATE);
    }

    @DisplayName("업무 거절은 500 대신 해당하는 400·404·409로 응답한다")
    @Test
    void mapsBusinessRejectionsTo400404Or409InsteadOf500() throws Exception {
        mvc.perform(
                        patch("/v1/sales/{id}/status", salesId)
                                .header("Authorization", sellerBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"SOLD_OUT\"}"))
                .andExpect(status().isBadRequest());
        Order order = create();
        mvc.perform(
                        get("/v1/orders/{number}", order.getOrderNumber())
                                .header("Authorization", bearer(MEMBER_B)))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/v1/orders/{number}/payments/999", order.getOrderNumber())
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isNotFound());
        orders.cancelBeforePayment(order.getOrderNumber(), "11111111-1111-4111-8111-111111111111");
        mvc.perform(
                        post("/v1/orders/{number}/cancel", order.getOrderNumber())
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isNoContent());
        assertThat(legacyStock(salesId).getAvailable()).isEqualTo(1);
        mvc.perform(
                        post("/v1/orders/{number}/payments", order.getOrderNumber())
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isConflict());
    }
}
