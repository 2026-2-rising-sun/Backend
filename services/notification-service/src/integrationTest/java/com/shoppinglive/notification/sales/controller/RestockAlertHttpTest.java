package com.shoppinglive.notification.sales.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import com.shoppinglive.notification.sales.repository.RestockAlertRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(
        properties = {
            "spring.datasource.url=${NOTIFICATION_TEST_POSTGRES_URL:jdbc:h2:mem:restock_alert_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH}",
        })
@AutoConfigureMockMvc
@DisplayName("재입고 알림 신청 실제 HTTP 계약")
class RestockAlertHttpTest extends NotificationSecuritySupport {

    private static final long SOLD_OUT_PRODUCT = 501L;
    private static final long ON_SALE_PRODUCT = 502L;
    private static final long UNKNOWN_PRODUCT = 999L;

    @Autowired MockMvc mvc;
    @Autowired RestockAlertRepository alerts;
    @Autowired ProductSalesStatusRepository statuses;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareSalesStatus() {
        clean();
        statuses.apply(new ProductSalesStatus(SOLD_OUT_PRODUCT, 9001, 1, false, "SOLD_OUT"));
        statuses.apply(new ProductSalesStatus(ON_SALE_PRODUCT, 9002, 1, true, "ON_SALE"));
    }

    @AfterEach
    void cleanFixtures() {
        clean();
    }

    @Test
    @DisplayName("같은 상품을 두 번 신청해도 성공하고 신청은 하나만 남는다")
    void registeringTwiceKeepsOneAlert() throws Exception {
        register(SOLD_OUT_PRODUCT).andExpect(status().isCreated());
        register(SOLD_OUT_PRODUCT).andExpect(status().isCreated());

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, SOLD_OUT_PRODUCT)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM restock_alert", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("신청한 적이 없는 상품을 해제해도 성공한다")
    void unregisteringWithoutRegistrationSucceeds() throws Exception {
        mvc.perform(
                        delete("/v1/restock-alerts/{id}", SOLD_OUT_PRODUCT)
                                .header("Authorization", userBearer()))
                .andExpect(status().isNoContent());

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, SOLD_OUT_PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("판매 중인 상품은 409 — 재입고 알림은 품절을 전제한다")
    void registeringInStockProductIsConflict() throws Exception {
        register(ON_SALE_PRODUCT)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));

        assertThat(alerts.exists(JwtTestTokens.MEMBER_A, ON_SALE_PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("판매정보를 받지 못한 상품은 404")
    void registeringUnknownProductIsNotFound() throws Exception {
        register(UNKNOWN_PRODUCT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("productId 가 없거나 0 이하면 400")
    void rejectsInvalidProductId() throws Exception {
        mvc.perform(
                        post("/v1/restock-alerts")
                                .header("Authorization", userBearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());

        register(0).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그인하지 않으면 신청할 수 없다")
    void registrationRequiresLogin() throws Exception {
        mvc.perform(
                        post("/v1/restock-alerts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":501}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions register(long productId) throws Exception {
        return mvc.perform(
                post("/v1/restock-alerts")
                        .header("Authorization", userBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + "}"));
    }

    private void clean() {
        jdbc.update("DELETE FROM restock_alert");
        jdbc.update("DELETE FROM product_sales_status");
    }
}
