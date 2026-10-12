package com.shoppinglive.shopping.coupon.infrastructure;

import com.shoppinglive.shopping.coupon.application.ProductCouponClient;
import com.shoppinglive.shopping.coupon.application.ProductCouponService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 쿠폰 조회 빈. Commerce 주소·토큰·timeout 은 판매정보와 같은 {@code commerceRestClient} 를 쓴다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductCouponProperties.class)
public class ProductCouponConfiguration {

    @Bean
    ProductCouponClient productCouponClient(RestClient commerceRestClient) {
        return new HttpProductCouponClient(commerceRestClient);
    }

    @Bean
    ProductCouponService productCouponService(ProductCouponClient productCouponClient,
            ProductCouponProperties properties) {
        return new ProductCouponService(productCouponClient, properties.cacheTtl(), properties.failureTtl(),
            System::nanoTime);
    }
}
