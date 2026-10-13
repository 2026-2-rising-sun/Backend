package com.shoppinglive.shopping.coupon.infrastructure;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.shopping.coupon.application.ProductCoupon;
import com.shoppinglive.shopping.coupon.application.ProductCouponClient;
import com.shoppinglive.shopping.coupon.application.ProductCouponUnavailableException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Commerce 상품별 쿠폰 조회 구현체. 회원 정보를 보내지 않고 service token 으로 호출한다.
 *
 * <p>재시도와 서킷은 두지 않는다. 실패 결과를 빈 목록으로 짧게 캐시하므로 장애 중에도 상품당 호출은 캐시 TTL 마다
 * 한 번으로 제한되고, 재시도는 그 부하를 곱하기만 한다. 어떤 실패든 {@link ProductCouponUnavailableException} 하나로 알린다.
 */
@RequiredArgsConstructor
public class HttpProductCouponClient implements ProductCouponClient {

    // 제안 계약(미합의): Commerce 가 합의하기 전까지 이 경로와 응답 형태는 바뀔 수 있다. 바뀌면 이 클래스만 고친다.
    static final String PATH = "/v1/service/coupons";

    private static final ParameterizedTypeReference<ApiResponse<List<CommerceCouponResponse>>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {
        };

    /** baseUrl·service token·timeout 이 설정된 Commerce 클라이언트 */
    private final RestClient restClient;

    @Override
    public List<ProductCoupon> findByProductId(long productId) {
        try {
            ApiResponse<List<CommerceCouponResponse>> body = restClient.get()
                .uri(uri -> uri.path(PATH).queryParam("productId", productId).build())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(RESPONSE_TYPE);
            if (body == null || !body.success() || body.data() == null) {
                throw new IllegalStateException("commerce coupon response is not successful");
            }
            return body.data().stream().map(CommerceCouponResponse::toCoupon).toList();
        } catch (RuntimeException e) {
            throw new ProductCouponUnavailableException("commerce coupon lookup failed", e);
        }
    }
}
