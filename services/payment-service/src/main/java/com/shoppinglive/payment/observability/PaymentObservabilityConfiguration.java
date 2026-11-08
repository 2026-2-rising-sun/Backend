package com.shoppinglive.payment.observability;

import com.shoppinglive.common.observability.BusinessTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
@ConditionalOnProperty(
        prefix = "shoppinglive.observability",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PaymentObservabilityConfiguration {
    @Bean
    PaymentObservation paymentObservation(BusinessTelemetry telemetry) {
        return new PaymentObservation(telemetry);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    PendingWorkMetrics paymentPendingMetrics(DataSource source, MeterRegistry registry) {
        return new PendingWorkMetrics(source, registry);
    }
}
