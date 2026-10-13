package com.shoppinglive.shopping.coupon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shoppinglive.shopping.coupon.application.ProductCoupon;
import com.shoppinglive.shopping.coupon.application.ProductCouponUnavailableException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpProductCouponClientTest {

    private static final String BASE_URL = "http://commerce.test";
    private static final String COUPON_URL = BASE_URL + "/v1/service/coupons?productId=7";
    private static final String BODY = """
        {"success":true,"data":[{"id":"33333333-3333-4333-8333-333333333334","name":"가을 니트 5천원 할인",
        "fixedDiscount":5000,"soldOut":false,"endsAt":"2026-10-15T11:00:00Z","expiresAt":"2026-11-15T00:00:00Z"}],
        "error":null}
        """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpProductCouponClient client = new HttpProductCouponClient(builder.build());

    @Test
    @DisplayName("Commerce 응답 envelope 의 data 를 쿠폰 목록으로 바꾸고, 요청한 productId 로 조회한다")
    void parsesProposedEnvelopeIntoCouponsForTheRequestedProduct() {
        // given
        server.expect(requestTo(COUPON_URL)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        // when
        List<ProductCoupon> coupons = client.findByProductId(7L);

        // then
        assertThat(coupons).containsExactly(new ProductCoupon("33333333-3333-4333-8333-333333333334",
            "가을 니트 5천원 할인", 5_000L, false,
            Instant.parse("2026-10-15T11:00:00Z"), Instant.parse("2026-11-15T00:00:00Z")));
        server.verify();
    }

    @Test
    @DisplayName("Commerce 가 음수 정액 할인을 주면 ProductCouponUnavailableException 으로 알린다")
    void negativeFixedDiscountIsReportedAsUnavailable() {
        // given
        server.expect(requestTo(COUPON_URL)).andRespond(withSuccess(BODY.replace("\"fixedDiscount\":5000",
            "\"fixedDiscount\":-1"), MediaType.APPLICATION_JSON));

        // when / then
        assertThatThrownBy(() -> client.findByProductId(7L)).isInstanceOf(ProductCouponUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1"})
    @DisplayName("UUID 계약을 위반한 쿠폰 ID는 조회 실패로 처리한다")
    void invalidCouponIdIsReportedAsUnavailable(String invalidId) {
        server.expect(requestTo(COUPON_URL)).andRespond(withSuccess(
            BODY.replace("33333333-3333-4333-8333-333333333334", invalidId), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findByProductId(7L)).isInstanceOf(ProductCouponUnavailableException.class);
        server.verify();
    }

    @Test
    @DisplayName("Commerce 가 서버 오류를 응답하면 ProductCouponUnavailableException 으로 알린다")
    void commerceServerErrorIsReportedAsUnavailable() {
        // given
        server.expect(requestTo(COUPON_URL)).andRespond(withServerError());

        // when / then
        assertThatThrownBy(() -> client.findByProductId(7L)).isInstanceOf(ProductCouponUnavailableException.class);
    }
}
