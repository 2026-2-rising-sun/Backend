package com.shoppinglive.commerce.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.order.service.OrderCreationService;
import com.shoppinglive.commerce.order.service.OrderExpirationScheduler;
import com.shoppinglive.commerce.order.service.OrderService;
import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import com.shoppinglive.commerce.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.payment.service.PaymentService;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commerce.sales.service.SalesService;
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
class CommerceStateTransitionTest
        extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {
    @Autowired OrderCreationService creation;
    @Autowired OrderService orders;
    @Autowired OrderExpirationScheduler expiration;
    @Autowired com.shoppinglive.commerce.order.service.PaymentGroupService groups;

    @Autowired
    com.shoppinglive.commerce.order.service.PaymentGroupExpirationScheduler groupExpiration;

    @Autowired PaymentService payments;
    @Autowired SalesService sales;
    @Autowired OrderJpaRepository orderRepository;
    @Autowired PaymentAttemptJpaRepository paymentRepository;
    @Autowired SalesJpaRepository salesRepository;
    @Autowired SalesStockJpaRepository stockRepository;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private Long salesId;

    @BeforeEach
    void setUp() {
        clean();
        shopping.register(new ProductSnapshot(920L, "마지막 재고", null));
        salesId = salesRepository.save(new Sales(920L, 10_000L, SalesStatus.ON_SALE)).getId();
        stockRepository.save(new SalesStock(salesId, 1, 0));
    }

    @AfterEach
    void clean() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        stockRepository.deleteAll();
        salesRepository.deleteAll();
        shopping.clear();
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
                transaction.executeWithoutResult(
                        tx ->
                                orderRepository.transitionStatus(
                                        order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
                PaymentAttempt attempt =
                        paymentRepository.save(
                                new PaymentAttempt(
                                        order.getId(),
                                        PaymentScenario.INSTANT_FAIL,
                                        Instant.now()));
                assertThat(payments.resolvePayment(attempt.getId())).isTrue();
                assertThat(payments.resolvePayment(attempt.getId())).isFalse();
            }
            default -> throw new IllegalArgumentException(path);
        }
    }

    @DisplayName("마지막 예약 재고를 복구하면 다시 주문할 수 있다")
    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expire", "fail"})
    void allowsNewOrderAfterRestoringLastReservedItem(String path) {
        Order order = create();
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.SOLD_OUT);
        release(order, path);
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isEqualTo(1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.ON_SALE);
        assertThat(create().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @DisplayName("재고를 복구해도 관리자가 지정한 비공개 상태를 유지한다")
    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expire", "fail"})
    void preservesPrivateStatusWhenRestoringStock(String path) {
        Order order = create();
        sales.changeStatus(salesId, SalesStatus.PRIVATE);
        release(order, path);
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isEqualTo(1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.PRIVATE);
    }

    @DisplayName("실제 스케줄 진입점에서 재고 복구에 실패하면 주문 만료도 롤백한다")
    @Test
    void rollsBackExpirationWhenScheduledStockRestoreFails() {
        Order order = create();
        jdbc.update(
                "UPDATE payment_group SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                order.getPaymentGroup().getId());
        jdbc.update("UPDATE sales_stock SET reserved = 0 WHERE sales_info_id = ?", salesId);
        assertThatThrownBy(() -> groups.expire(order.getPaymentGroup().getId()))
                .isInstanceOf(IllegalStateException.class);
        // 묶음 스케줄러는 실패한 묶음 하나를 분리해 로그로 남긴다.
        // 해당 트랜잭션은 롤백된 상태여야 한다.
        groupExpiration.run();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isZero();
    }

    @DisplayName("결제의 재고 처리에 실패하면 결제·주문 확정도 롤백한다")
    @ParameterizedTest
    @EnumSource(
            value = PaymentScenario.class,
            names = {"INSTANT_SUCCESS", "INSTANT_FAIL"})
    void rollsBackPaymentAndOrderConfirmationWhenStockUpdateFails(PaymentScenario scenario) {
        Order order = create();
        transaction.executeWithoutResult(
                tx ->
                        orderRepository.transitionStatus(
                                order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
        PaymentAttempt attempt =
                paymentRepository.save(new PaymentAttempt(order.getId(), scenario, Instant.now()));
        jdbc.update("UPDATE sales_stock SET reserved = 0 WHERE sales_info_id = ?", salesId);
        assertThatThrownBy(() -> payments.resolvePayment(attempt.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(paymentRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
    }

    @DisplayName("재고가 0이면 ON_SALE로 변경하지 않는다")
    @ParameterizedTest
    @EnumSource(
            value = SalesStatus.class,
            names = {"READY", "ON_SALE", "SOLD_OUT", "PRIVATE"})
    void rejectsOnSaleTransitionWhenStockIsZero(SalesStatus current) throws Exception {
        jdbc.update("UPDATE sales_info SET status = ? WHERE id = ?", current.name(), salesId);
        jdbc.update("UPDATE sales_stock SET available = 0 WHERE sales_info_id = ?", salesId);
        var result =
                mvc.perform(
                        patch("/v1/sales/{id}/status", salesId)
                                .header("Authorization", adminBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"ON_SALE\"}"));
        if (current == SalesStatus.READY || current == SalesStatus.ON_SALE) {
            result.andExpect(status().isConflict());
        } else {
            result.andExpect(status().isOk());
            assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                    .isEqualTo(SalesStatus.SOLD_OUT);
        }
    }

    @DisplayName("관리자가 재고를 보충하면 품절만 해제하고 다른 상태는 유지한다")
    @Test
    void reopensOnlySoldOutSalesAfterStockReplenishment() {
        create();
        sales.adjustAvailable(salesId, 1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.ON_SALE);
        sales.changeStatus(salesId, SalesStatus.PRIVATE);
        sales.adjustAvailable(salesId, 1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus())
                .isEqualTo(SalesStatus.PRIVATE);
    }

    @DisplayName("업무 거절은 500 대신 해당하는 400·404·409로 응답한다")
    @Test
    void mapsBusinessRejectionsTo400404Or409InsteadOf500() throws Exception {
        mvc.perform(
                        patch("/v1/sales/{id}/status", salesId)
                                .header("Authorization", adminBearer())
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
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isEqualTo(1);
        mvc.perform(
                        post("/v1/orders/{number}/payments", order.getOrderNumber())
                                .header("Authorization", bearer(MEMBER_A)))
                .andExpect(status().isConflict());
    }
}
