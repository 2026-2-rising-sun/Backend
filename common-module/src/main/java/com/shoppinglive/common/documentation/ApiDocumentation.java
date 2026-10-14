package com.shoppinglive.common.documentation;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** 문서 조립만 재사용한다. 서비스 메타데이터와 지원 인증 방식은 호출자가 지정한다. */
public final class ApiDocumentation {
    private ApiDocumentation() {}

    public static OpenAPI create(String title, String version, boolean serviceToken) {
        Components components =
                new Components()
                        .addSecuritySchemes(
                                "BearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"));
        if (serviceToken) {
            components.addSecuritySchemes(
                    "ServiceToken",
                    new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.HEADER)
                            .name("X-Service-Token"));
        }
        return new OpenAPI().info(new Info().title(title).version(version)).components(components);
    }
}
