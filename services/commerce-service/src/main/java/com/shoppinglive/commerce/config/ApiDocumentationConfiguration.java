package com.shoppinglive.commerce.config;

import com.shoppinglive.common.documentation.ApiDocumentation;
import com.shoppinglive.common.documentation.DocumentationSecurityConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
@Import(DocumentationSecurityConfiguration.class)
public class ApiDocumentationConfiguration {
    @Bean
    OpenAPI apiDocumentation() {
        return ApiDocumentation.create("Commerce Service API", "2.0.0", true);
    }
}
