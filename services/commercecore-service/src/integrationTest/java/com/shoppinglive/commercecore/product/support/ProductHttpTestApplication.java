package com.shoppinglive.commercecore.product.support;

import com.shoppinglive.commercecore.config.CommerceCoreSecurityConfiguration;
import com.shoppinglive.commercecore.product.entity.Product;
import com.shoppinglive.commercecore.product.repository.ProductRepository;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.common.outbox.OutboxConfiguration;
import com.shoppinglive.common.outbox.OutboxMessage;
import com.shoppinglive.common.outbox.OutboxMessageRepository;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.security.AuthenticationConfiguration;
import com.shoppinglive.common.web.WebConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** 상품 HTTP 회귀는 실제 보안 필터·상품·판매 원본을 사용하며 다른 업무 서버에 의존하지 않는다. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {Product.class, Sales.class, OutboxMessage.class})
@EnableJpaRepositories(
        basePackageClasses = {
            ProductRepository.class,
            SalesJpaRepository.class,
            OutboxMessageRepository.class
        })
@ComponentScan(
        basePackages = {
            "com.shoppinglive.commercecore.product",
            "com.shoppinglive.commercecore.storage"
        },
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*"))
@Import({
    JpaAuditingConfig.class,
    WebConfiguration.class,
    AuthenticationConfiguration.class,
    OutboxConfiguration.class,
    CommerceCoreSecurityConfiguration.class
})
public class ProductHttpTestApplication {}
