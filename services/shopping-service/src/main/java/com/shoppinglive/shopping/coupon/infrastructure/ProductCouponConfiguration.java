package com.shoppinglive.shopping.coupon.infrastructure;

import com.shoppinglive.shopping.coupon.application.ProductCouponClient;
import com.shoppinglive.shopping.coupon.application.ProductCouponService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** 조회 로드용 가상 스레드 실행기. 블로킹 HTTP 가 공용 풀을 쓰지 않으며, 종료 때 진행 중 조회를 기다린다. */
    @Bean(destroyMethod = "close")
    ExecutorService productCouponExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ProductCouponService productCouponService(ProductCouponClient productCouponClient,
            ProductCouponProperties properties, ExecutorService productCouponExecutor) {
        return new ProductCouponService(productCouponClient, properties.cacheTtl(), System::nanoTime,
            productCouponExecutor);
    }
}
