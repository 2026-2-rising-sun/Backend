package com.shoppinglive.common.web;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
public class WebConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonTypePolicy() {
        return new JsonTypePolicy();
    }

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

    /** 외부 REST 호출 시 수신한 요청의 correlation ID를 다음 서비스로 전달한다. */
    @Bean
    @ConditionalOnClass(RestClient.class)
    public RestClientCustomizer correlationIdRestClientCustomizer() {
        return builder ->
                builder.requestInterceptor(
                        (request, body, execution) -> {
                            String requestId = MDC.get(CorrelationId.MDC_KEY);
                            if (StringUtils.hasText(requestId)) {
                                request.getHeaders().set(CorrelationId.HEADER, requestId);
                            }
                            return execution.execute(request, body);
                        });
    }
}
