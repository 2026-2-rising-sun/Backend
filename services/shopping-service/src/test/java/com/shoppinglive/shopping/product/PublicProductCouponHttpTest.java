package com.shoppinglive.shopping.product;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.shopping.coupon.application.ProductCoupon;
import com.shoppinglive.shopping.coupon.application.ProductCouponClient;
import com.shoppinglive.shopping.coupon.application.ProductCouponUnavailableException;
import com.shoppinglive.shopping.security.ShoppingSecuritySupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 쿠폰 공개 경로의 HTTP 계약. 상품별 캐시가 테스트 간에 섞이지 않도록 상품 id 를 테스트마다 다르게 쓴다. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:product_coupon_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"})
@AutoConfigureMockMvc
class PublicProductCouponHttpTest extends ShoppingSecuritySupport {

    @Autowired MockMvc mvc;
    @MockitoBean ProductCouponClient commerce;

    @Test
    @DisplayName("인증 없이 상품 쿠폰 목록을 조회하면 200 과 쿠폰 항목이 응답된다")
    void anonymousReadReturnsCouponItems() throws Exception {
        // given
        ProductCoupon coupon = new ProductCoupon("33333333-3333-4333-8333-333333333334", "가을 니트 5천원 할인",
            5_000L, false, Instant.parse("2026-10-15T11:00:00Z"), Instant.parse("2026-11-15T00:00:00Z"));
        when(commerce.findByProductId(9101L)).thenReturn(List.of(coupon));

        // when / then
        mvc.perform(get("/v1/products/9101/coupons"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data[0].id").value("33333333-3333-4333-8333-333333333334"))
            .andExpect(jsonPath("$.data[0].fixedDiscount").value(5000))
            .andExpect(jsonPath("$.data[0].soldOut").value(false));
    }

    @Test
    @DisplayName("Commerce 를 쓸 수 없으면 500 대신 200 과 빈 쿠폰 목록을 응답한다")
    void unavailableCommerceAnswersEmptyListInsteadOfServerError() throws Exception {
        // given
        when(commerce.findByProductId(9102L)).thenThrow(new ProductCouponUnavailableException("commerce down"));

        // when / then
        mvc.perform(get("/v1/products/9102/coupons"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("상품 id 가 숫자가 아니면 인증 없이도 다른 상품 API 와 같은 INVALID_REQUEST 400 을 응답한다")
    void malformedIdIsAnAnonymousInputErrorLikeSiblings() throws Exception {
        mvc.perform(get("/v1/products/abc/coupons"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("쿠폰 경로에 GET 이 아닌 메서드를 인증 없이 요청하면 401 로 거부된다")
    void otherMethodsOnCouponPathAreRejectedAnonymously() throws Exception {
        mvc.perform(post("/v1/products/9103/coupons")).andExpect(status().isUnauthorized());
    }
}
