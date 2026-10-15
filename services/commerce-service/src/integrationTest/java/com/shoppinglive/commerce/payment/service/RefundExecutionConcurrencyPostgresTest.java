package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.commerce.payment.entity.RefundStatus;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class RefundExecutionConcurrencyPostgresTest extends RefundTestSupport {
    @Autowired RefundIntakeService refunds;
    @Autowired RefundExecutionService executions;

    @DisplayName("환불 요청 하나를 동시에 실행해도 게이트웨이 결과 하나만 생성한다")
    @Test
    void concurrentExecutionOfOneRequestCreatesOneGatewayResult() throws Exception {
        var group = createAndPay();
        var request =
                refunds.request(
                                MEMBER_A,
                                group.groupNumber(),
                                "execute-concurrent",
                                List.of(a.getId()))
                        .refund();
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return executions.execute(request.id());
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return executions.execute(request.id());
                            });
            start.countDown();

            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result WHERE refund_request_id=?",
                                Integer.class,
                                request.id()))
                .isEqualTo(1);
        assertThat(refunds.get(MEMBER_A, group.groupNumber(), request.id()).status())
                .isEqualTo(RefundStatus.SUCCESS);
    }
}
