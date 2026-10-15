package com.shoppinglive.shopping.coupon.service;

import com.shoppinglive.common.web.ServiceRestClientFactory;
import com.shoppinglive.shopping.client.commerce.HttpCouponSnapshotClient;
import com.shoppinglive.shopping.client.commerce.SalesClientProperties;
import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * 쿠폰 동기화는 {@code shopping.coupons.sync.enabled=true} 일 때만 켠다. 꺼져 있으면 scheduler 도 만들지 않는다.
 *
 * <p>Commerce 주소·service token 은 판매정보 클라이언트 설정({@code shopping.sales-client.*})을 같이 쓴다. 같은 Commerce
 * 를 부르고, 값 검증은 {@code SalesClientConfiguration} 이 기동 때 이미 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "shopping.coupons.sync", name = "enabled", havingValue = "true")
class CouponSyncConfiguration {

    @Bean
    CouponSync couponSync(
            RestClient.Builder restClientBuilder,
            SalesClientProperties properties,
            CouponDisplayRepository replica) {
        RestClient restClient =
                ServiceRestClientFactory.create(
                        restClientBuilder,
                        properties.baseUrl(),
                        properties.serviceToken(),
                        properties.connectTimeout(),
                        properties.readTimeout());
        return new CouponSync(new HttpCouponSnapshotClient(restClient), replica, Clock.systemUTC());
    }
}
