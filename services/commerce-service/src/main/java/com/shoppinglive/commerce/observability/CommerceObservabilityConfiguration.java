package com.shoppinglive.commerce.observability;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class CommerceObservabilityConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    PendingWorkMetrics pendingWorkMetrics(DataSource source, MeterRegistry registry) {
        return new PendingWorkMetrics(source, registry);
    }
}
