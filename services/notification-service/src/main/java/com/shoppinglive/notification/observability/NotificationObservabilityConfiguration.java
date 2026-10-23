package com.shoppinglive.notification.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class NotificationObservabilityConfiguration {
    /** 창 길이는 시작 임박 알림(10분)과 같게 둔다. 파드가 뜨는 데 걸리는 시간이 길면 늘린다. */
    @Bean(initMethod = "start", destroyMethod = "close")
    UpcomingBroadcastAlertMetrics upcomingBroadcastAlertMetrics(
            DataSource source,
            MeterRegistry registry,
            @Value("${shoppinglive.observability.upcoming-broadcast-window:10m}") Duration window) {
        return new UpcomingBroadcastAlertMetrics(source, registry, window);
    }
}
