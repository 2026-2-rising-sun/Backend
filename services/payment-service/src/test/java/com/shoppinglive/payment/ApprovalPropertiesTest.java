package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.payment.payment.service.ApprovalProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApprovalPropertiesTest {
    @EnableConfigurationProperties(ApprovalProperties.class)
    static class Config {}

    @Test
    @DisplayName("[PAY-CONFIRM-11] 승인 응답 대기 기본값은 8초이고 Commerce 호출 타임아웃 10초보다 짧다")
    void defaultResponseWaitIsEightSeconds() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                .run(
                        context -> {
                            Duration wait =
                                    context.getBean(ApprovalProperties.class).responseWait();
                            assertThat(wait).isEqualTo(Duration.ofSeconds(8));
                            assertThat(wait).isLessThan(Duration.ofSeconds(10));
                        });
    }
}
