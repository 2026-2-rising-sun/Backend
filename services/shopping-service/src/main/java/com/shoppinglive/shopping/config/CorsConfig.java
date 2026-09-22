package com.shoppinglive.shopping.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 다른 origin 에서 도는 개발용 프론트가 API 를 부를 수 있게 한다. 허용 origin 이 비어 있으면(local 외 기본값)
 * 매핑 자체를 만들지 않는다. Ingress 로 같은 호스트에서 서빙되면 CORS 가 필요 없기 때문이다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${shopping.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/v1/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Idempotency-Key", "X-Request-Id")
                .exposedHeaders("X-Request-Id");
    }
}
