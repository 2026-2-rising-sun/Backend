package com.shoppinglive.commerce.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commerce.orders.application.*;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.application.PaymentService;
import com.shoppinglive.commerce.payments.domain.*;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.*;
import com.shoppinglive.commerce.sales.infrastructure.*;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class CommerceReviewRegressionTest {
    @Autowired OrderCreationService creation;
    @Autowired OrderService orders;
    @Autowired OrderExpirationScheduler expiration;
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
        return creation.create(new CreateOrderCommand(920L, 1, "구매자", "010-1234-5678", "secret", 10_000L), null).order();
    }

    private void release(Order order, String path) {
        switch (path) {
            case "cancel" -> orders.cancelBeforePayment(order.getOrderNumber(), "secret");
            case "expire" -> {
                jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), order.getId());
                expiration.runScheduled();
            }
            case "fail" -> {
                transaction.executeWithoutResult(tx -> orderRepository.transitionStatus(
                    order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
                PaymentAttempt attempt = paymentRepository.save(new PaymentAttempt(
                    order.getId(), PaymentScenario.INSTANT_FAIL, Instant.now()));
                assertThat(payments.resolvePayment(attempt.getId())).isTrue();
                assertThat(payments.resolvePayment(attempt.getId())).isFalse();
            }
            default -> throw new IllegalArgumentException(path);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expire", "fail"})
    void 마지막_재고_복원후_다시_주문할_수_있다(String path) {
        Order order = create();
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus()).isEqualTo(SalesStatus.SOLD_OUT);
        release(order, path);
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isEqualTo(1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus()).isEqualTo(SalesStatus.ON_SALE);
        assertThat(create().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expire", "fail"})
    void 재고를_복원해도_관리자_비공개는_보존한다(String path) {
        Order order = create();
        sales.changeStatus(salesId, SalesStatus.PRIVATE);
        release(order, path);
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isEqualTo(1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus()).isEqualTo(SalesStatus.PRIVATE);
    }

    @Test
    void 실제_스케줄_진입점에서_복원_실패하면_만료도_롤백한다() {
        Order order = create();
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)), order.getId());
        jdbc.update("UPDATE sales_stock SET reserved = 0 WHERE sales_info_id = ?", salesId);
        assertThatThrownBy(expiration::runScheduled).isInstanceOf(IllegalStateException.class);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(stockRepository.findById(salesId).orElseThrow().getAvailable()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentScenario.class, names = {"INSTANT_SUCCESS", "INSTANT_FAIL"})
    void 결제_재고처리_실패시_결제와_주문_확정도_롤백한다(PaymentScenario scenario) {
        Order order = create();
        transaction.executeWithoutResult(tx -> orderRepository.transitionStatus(
            order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING"));
        PaymentAttempt attempt = paymentRepository.save(new PaymentAttempt(order.getId(), scenario, Instant.now()));
        jdbc.update("UPDATE sales_stock SET reserved = 0 WHERE sales_info_id = ?", salesId);
        assertThatThrownBy(() -> payments.resolvePayment(attempt.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(paymentRepository.findById(attempt.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
    }

    @ParameterizedTest
    @EnumSource(value = SalesStatus.class, names = {"READY", "ON_SALE", "SOLD_OUT", "PRIVATE"})
    void 재고0이면_ON_SALE로_변경되지_않는다(SalesStatus current) throws Exception {
        jdbc.update("UPDATE sales_info SET status = ? WHERE id = ?", current.name(), salesId);
        jdbc.update("UPDATE sales_stock SET available = 0 WHERE sales_info_id = ?", salesId);
        var result = mvc.perform(patch("/v1/sales/{id}/status", salesId)
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_SALE\"}"));
        if (current == SalesStatus.READY || current == SalesStatus.ON_SALE) {
            result.andExpect(status().isConflict());
        } else {
            result.andExpect(status().isOk());
            assertThat(salesRepository.findById(salesId).orElseThrow().getStatus()).isEqualTo(SalesStatus.SOLD_OUT);
        }
    }

    @Test
    void 관리자_재고_보충은_품절만_해제한다() {
        create();
        sales.adjustAvailable(salesId, 1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus()).isEqualTo(SalesStatus.ON_SALE);
        sales.changeStatus(salesId, SalesStatus.PRIVATE);
        sales.adjustAvailable(salesId, 1);
        assertThat(salesRepository.findById(salesId).orElseThrow().getStatus()).isEqualTo(SalesStatus.PRIVATE);
    }

    @Test
    void 비즈니스_거절은_500이_아닌_400_404_409이다() throws Exception {
        mvc.perform(patch("/v1/sales/{id}/status", salesId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SOLD_OUT\"}")).andExpect(status().isBadRequest());
        Order order = create();
        mvc.perform(get("/v1/orders/{number}", order.getOrderNumber()).header("X-Order-Password", "wrong"))
            .andExpect(status().isNotFound());
        mvc.perform(get("/v1/orders/{number}/payments/999", order.getOrderNumber()).header("X-Order-Password", "secret"))
            .andExpect(status().isNotFound());
        orders.cancelBeforePayment(order.getOrderNumber(), "secret");
        mvc.perform(post("/v1/orders/{number}/cancel", order.getOrderNumber()).header("X-Order-Password", "secret"))
            .andExpect(status().isConflict());
        mvc.perform(post("/v1/orders/{number}/payments", order.getOrderNumber()).header("X-Order-Password", "secret"))
            .andExpect(status().isConflict());
    }
}
