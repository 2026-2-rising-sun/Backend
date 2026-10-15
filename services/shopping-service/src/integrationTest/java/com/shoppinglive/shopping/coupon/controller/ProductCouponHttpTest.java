package com.shoppinglive.shopping.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 상품 쿠폰 조회 API 의 입력 검증. 공용 예외 처리까지 거친 실제 응답을 확인한다. */
@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:shopping_product_coupon_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ProductCouponHttpTest extends ShoppingSecuritySupport {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @DisplayName("상품 ID 가 0 이하이면 빈 목록이 아니라 400 INVALID_REQUEST 를 준다")
    @ValueSource(strings = {"0", "-1"})
    void nonPositiveProductIdIsInvalid(String productId) throws Exception {
        mvc.perform(get("/v1/products/" + productId + "/coupons"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("쿠폰이 없는 정상 상품 ID 는 로그인 없이 빈 목록을 준다")
    void positiveProductIdWithoutCouponsIsEmpty() throws Exception {
        mvc.perform(get("/v1/products/1/coupons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
