package com.shoppinglive.commerce.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.orders.application.CreateOrderCommand;
import com.shoppinglive.commerce.orders.application.OrderCreationService;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.commerce.shopping.infrastructure.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.support.CommerceSecurityTestSupport;
import com.shoppinglive.common.security.test.JwtTestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MemberCommerceApiTest extends CommerceSecurityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CartItemRepository items;
    @Autowired OrderCreationService creation;
    @Autowired OrderJpaRepository orders;
    @Autowired PaymentAttemptJpaRepository payments;
    @Autowired SalesJpaRepository sales;
    @Autowired SalesStockJpaRepository stocks;
    @Autowired InMemoryShoppingClientStub shopping;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    private Long salesId;

    @BeforeEach
    void seed() {
        clean();
        shopping.register(new ProductSnapshot(1L, "상품", null));
        shopping.register(new ProductSnapshot(2L, "새 상품", null));
        salesId = sales.save(new Sales(1L, 1000L, SalesStatus.ON_SALE)).getId();
        stocks.save(new SalesStock(salesId, 10, 0));
    }

    @AfterEach
    void clean() {
        payments.deleteAll(); orders.deleteAll(); items.deleteAll(); stocks.deleteAll(); sales.deleteAll();
        shopping.clear();
    }

    private Order order(String member) {
        return creation.create(new CreateOrderCommand(1L, 1, "회원", "01012345678", member, null), null).order();
    }

    @Test
    void revokedOrUnavailableSessionsCannotTradeWhileServiceSalesRemainIndependent() throws Exception {
        String token = bearer(MEMBER_A);
        mvc.perform(get("/v1/cart/items").header("Authorization", token)).andExpect(status().isOk());
        accessSessions.revoke();
        mvc.perform(get("/v1/cart/items").header("Authorization", token)).andExpect(status().isUnauthorized());
        accessSessions.fail();
        mvc.perform(get("/v1/cart/items").header("Authorization", token)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/v1/orders").header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/v1/sales").param("productIds", "1").header("X-Service-Token", SHOPPING_TOKEN))
            .andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        assertThat(orders.count()).isZero();
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(10);
    }

    @Test
    void anonymousCannotUseAnyTransactionOrOldPasswordFallback() throws Exception {
        for (String path : List.of("/v1/orders", "/v1/orders/checkout?productId=1&quantity=1",
            "/v1/orders/guest", "/v1/orders/guest/payments/1", "/v1/cart/items")) {
            mvc.perform(get(path).header("X-Order-Password", "legacy"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        for (String path : List.of("/v1/orders", "/v1/orders/guest/cancel", "/v1/orders/guest/payments",
            "/v1/cart/items", "/v1/cart/items/1/orders")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        }
        assertThat(orders.count()).isZero();
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(10);
    }

    @Test
    void malformedExpiredAndForeignSignatureJwtAreRejectedByActualDecoder() throws Exception {
        String expired = TOKENS.sign(TOKENS.claims(MEMBER_A, Set.of("USER"))
            .issueTime(Date.from(Instant.now().minusSeconds(600)))
            .expirationTime(Date.from(Instant.now().minusSeconds(1))).build());
        String forged = new JwtTestTokens().token(MEMBER_A, Set.of("USER"));
        for (String token : List.of("malformed", expired, forged)) {
            mvc.perform(get("/v1/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void otherUserAndAdminCannotReadCancelPayOrListAnotherMembersOrder() throws Exception {
        Order order = order(MEMBER_A);
        for (String token : List.of(bearer(MEMBER_B), adminBearer())) {
            mvc.perform(get("/v1/orders/{number}", order.getOrderNumber()).header("Authorization", token))
                .andExpect(status().isNotFound());
            mvc.perform(post("/v1/orders/{number}/cancel", order.getOrderNumber()).header("Authorization", token))
                .andExpect(status().isNotFound());
            mvc.perform(post("/v1/orders/{number}/payments", order.getOrderNumber()).header("Authorization", token))
                .andExpect(status().isNotFound());
            mvc.perform(get("/v1/orders/{number}/payments/1", order.getOrderNumber()).header("Authorization", token))
                .andExpect(status().isNotFound());
            mvc.perform(get("/v1/orders").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        }
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payments.count()).isZero();
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isEqualTo(1);
    }

    @Test
    void paymentBodyCannotChooseScenarioAndUserCannotAccessDevControl() throws Exception {
        var order = order(MEMBER_A);
        mvc.perform(post("/v1/orders/{number}/payments", order.getOrderNumber())
                .header("Authorization", bearer(MEMBER_A)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"INSTANT_SUCCESS\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/dev/payment-scenarios/{number}", order.getOrderNumber())
                .header("Authorization", bearer(MEMBER_A)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scenario\":\"INSTANT_SUCCESS\"}"))
            .andExpect(status().isForbidden());
        assertThat(payments.count()).isZero();
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void cartOrderPaymentAndHistoryUseSameMemberAndPreserveParentPaymentOwnership() throws Exception {
        String cartBody = mvc.perform(post("/v1/cart/items").header("Authorization", bearer(MEMBER_A))
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"quantity\":2}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long itemId = mapper.readTree(cartBody).get("id").asLong();
        for (String token : List.of(bearer(MEMBER_B), adminBearer())) {
            mvc.perform(patch("/v1/cart/items/{id}", itemId).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isNotFound());
            mvc.perform(delete("/v1/cart/items/{id}", itemId).header("Authorization", token))
                .andExpect(status().isNotFound());
            mvc.perform(post("/v1/cart/items/{id}/orders", itemId).header("Authorization", token)
                    .header("X-Idempotency-Key", "checkout").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"buyerName\":\"회원\",\"buyerPhone\":\"01012345678\",\"expectedTotalAmount\":2000}"))
                .andExpect(status().isNotFound());
        }
        String result = mvc.perform(post("/v1/cart/items/{id}/orders", itemId)
                .header("Authorization", bearer(MEMBER_A)).header("X-Idempotency-Key", "checkout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"buyerName\":\"회원\",\"buyerPhone\":\"01012345678\",\"expectedTotalAmount\":2000}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String number = mapper.readTree(result).get("orderNumber").asText();
        String paymentBody = mvc.perform(post("/v1/orders/{number}/payments", number)
                .header("Authorization", bearer(MEMBER_A)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long paymentId = mapper.readTree(paymentBody).get("paymentId").asLong();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(orders.findByOrderNumber(number).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID));
        mvc.perform(get("/v1/orders/{number}/payments/{id}", number, paymentId).header("Authorization", bearer(MEMBER_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        var another = order(MEMBER_A);
        mvc.perform(get("/v1/orders/{number}/payments/{id}", another.getOrderNumber(), paymentId)
                .header("Authorization", bearer(MEMBER_A))).andExpect(status().isNotFound());
        mvc.perform(get("/v1/orders").header("Authorization", bearer(MEMBER_A)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        assertThat(items.count()).isZero();
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(7);
    }

    @Test
    void userCannotManageSalesWhileAdminCanAndCallerCannotActAsMember() throws Exception {
        mvc.perform(post("/v1/sales").header("Authorization", bearer(MEMBER_A))
                .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":2,\"price\":1000,\"initialStock\":5}"))
            .andExpect(status().isForbidden());
        for (String path : List.of("price", "stock", "status")) {
            mvc.perform(patch("/v1/sales/{id}/{path}", salesId, path).header("Authorization", bearer(MEMBER_A))
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get("/v1/sales/{id}/stock", salesId).header("Authorization", bearer(MEMBER_A)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/v1/sales/{id}/stock", salesId).header("Authorization", adminBearer()))
            .andExpect(status().isOk());
        mvc.perform(post("/v1/sales").header("Authorization", adminBearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":2,\"price\":1000,\"initialStock\":5}"))
            .andExpect(status().isCreated());
        mvc.perform(get("/v1/orders").header("X-Service-Token", SHOPPING_TOKEN)).andExpect(status().isUnauthorized());
    }

    @Test
    void bulkSalesRequiresAllowedCallerAndHealthOnlyExposesProbes() throws Exception {
        mvc.perform(get("/v1/sales?productIds=1").header("Authorization", adminBearer()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/sales?productIds=1").header("X-Service-Token", "wrong"))
            .andExpect(status().isUnauthorized());
        for (String token : List.of(SHOPPING_TOKEN, LIVE_TOKEN)) {
            mvc.perform(get("/v1/sales?productIds=1").header("X-Service-Token", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].productId").value(1));
        }
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 3, Integer.MAX_VALUE})
    void amountOverflowRejectsPreviewDirectAndCartWithoutMutation(int quantity) throws Exception {
        transaction.executeWithoutResult(status -> sales.findById(salesId).orElseThrow().changePrice(Long.MAX_VALUE));
        mvc.perform(get("/v1/orders/checkout?productId=1&quantity=" + quantity).header("Authorization", bearer(MEMBER_A)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/orders").header("Authorization", bearer(MEMBER_A)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"quantity\":" + quantity + ",\"buyerName\":\"회원\",\"buyerPhone\":\"01012345678\"}"))
            .andExpect(status().isBadRequest());
        var item = items.saveAndFlush(new com.shoppinglive.commerce.cart.domain.CartItem(MEMBER_A, 1L, quantity));
        mvc.perform(post("/v1/cart/items/{id}/orders", item.getId()).header("Authorization", bearer(MEMBER_A))
                .header("X-Idempotency-Key", "overflow").contentType(MediaType.APPLICATION_JSON)
                .content("{\"buyerName\":\"회원\",\"buyerPhone\":\"01012345678\",\"expectedTotalAmount\":1}"))
            .andExpect(status().isBadRequest());
        assertThat(orders.count()).isZero();
        assertThat(items.existsById(item.getId())).isTrue();
        assertThat(stocks.findById(salesId).orElseThrow().getAvailable()).isEqualTo(10);
        assertThat(stocks.findById(salesId).orElseThrow().getReserved()).isZero();
    }

}
