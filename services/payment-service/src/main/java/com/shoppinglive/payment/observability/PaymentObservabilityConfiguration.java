package com.shoppinglive.payment.observability;

import com.shoppinglive.payment.payment.service.RecoveryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class PaymentObservabilityConfiguration {
    @Bean
    UnresolvedWorkMetrics unresolvedWorkMetrics(
            DataSource source, MeterRegistry registry, RecoveryProperties recovery) {
        return new UnresolvedWorkMetrics(source, registry, recovery);
    }
}
