package com.shoppinglive.shopping.sales.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 판매정보 어댑터 설정. stub 은 {@code @Component} 로 스스로 등록된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SalesClientProperties.class)
public class SalesClientConfiguration {
}
