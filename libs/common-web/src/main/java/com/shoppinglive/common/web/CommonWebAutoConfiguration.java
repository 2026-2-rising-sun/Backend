package com.shoppinglive.common.web;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnClass(name = "jakarta.servlet.Filter")
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** Propagates the inbound correlation id to downstream services on outgoing REST calls. */
    @Bean
    @ConditionalOnClass(RestClient.class)
    public RestClientCustomizer correlationIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String requestId = MDC.get(CorrelationId.MDC_KEY);
            if (StringUtils.hasText(requestId)) {
                request.getHeaders().set(CorrelationId.HEADER, requestId);
            }
            return execution.execute(request, body);
        });
    }
}
