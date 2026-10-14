package com.shoppinglive.common.documentation;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DocumentationSecurityConfigurationTest {
    @Test
    void 문서_비활성화나_설정누락이면_문서_보안체인을_등록하지_않는다() {
        var context =
                new ApplicationContextRunner()
                        .withUserConfiguration(DocumentationSecurityConfiguration.class);
        context.run(
                application -> assertThat(application).doesNotHaveBean("documentationSecurity"));
        context.withPropertyValues("springdoc.api-docs.enabled=false")
                .run(
                        application ->
                                assertThat(application).doesNotHaveBean("documentationSecurity"));
    }
}
