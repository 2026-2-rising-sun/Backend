package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.shoppinglive.payment.payment.service.ApprovingSweeper;
import com.shoppinglive.payment.payment.service.ExpiryProperties;
import com.shoppinglive.payment.payment.service.PaymentExpiryJob;
import com.shoppinglive.payment.payment.service.RecoveryProperties;
import com.shoppinglive.payment.payment.service.RecoveryScheduler;
import com.shoppinglive.payment.payment.service.UnknownPaymentPoller;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class RecoverySchedulerTest {
    @EnableScheduling
    @EnableConfigurationProperties({RecoveryProperties.class, ExpiryProperties.class})
    static class Config {}

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Config.class, RecoveryScheduler.class)
                .withBean(ApprovingSweeper.class, () -> mock(ApprovingSweeper.class))
                .withBean(UnknownPaymentPoller.class, () -> mock(UnknownPaymentPoller.class))
                .withBean(PaymentExpiryJob.class, () -> mock(PaymentExpiryJob.class));
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-01][PAY-RECOVER-02][PAY-EXPIRE-01] 기본 설정이면 스위퍼·조회기·만료 3개가 설계값 주기로 등록된다")
    void registersThreeJobsByDefault() {
        runner().run(
                        context -> {
                            assertThat(context).hasSingleBean(RecoveryScheduler.class);
                            assertThat(
                                            context.getBean(ScheduledTaskHolder.class)
                                                    .getScheduledTasks())
                                    .hasSize(3);
                            RecoveryProperties recovery = context.getBean(RecoveryProperties.class);
                            assertThat(recovery.sweepInterval()).isEqualTo(Duration.ofSeconds(30));
                            assertThat(recovery.sweepBatchSize()).isEqualTo(100);
                            assertThat(recovery.approvingStuckAfter())
                                    .isEqualTo(Duration.ofMinutes(3));
                            assertThat(recovery.pollInterval()).isEqualTo(Duration.ofSeconds(5));
                            assertThat(recovery.pollBatchSize()).isEqualTo(50);
                            assertThat(recovery.checkDeadline()).isEqualTo(Duration.ofMinutes(30));
                            ExpiryProperties expiry = context.getBean(ExpiryProperties.class);
                            assertThat(expiry.interval()).isEqualTo(Duration.ofMinutes(1));
                            assertThat(expiry.batchSize()).isEqualTo(100);
                        });
    }

    @Test
    @DisplayName("[PAY-RECOVER-01] payment.recovery.enabled=false면 스케줄러를 등록하지 않는다")
    void disabled() {
        runner().withPropertyValues("payment.recovery.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RecoveryScheduler.class));
    }
}
