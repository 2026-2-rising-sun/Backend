package com.shoppinglive.commerce.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.payment.entity.MockPaymentResultUnknownException;
import com.shoppinglive.commerce.payment.entity.MockPaymentResultUnknownException.Reason;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCE_TEST_POSTGRES_URL", matches = ".+")
class DurableMockGatewayPostgresTest {
    private Flyway flyway;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private DurableMockGateway gateway;

    @BeforeEach
    void createOwnedSchema() {
        String schema = "payment_gateway_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("COMMERCE_TEST_POSTGRES_URL");
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
                        System.getenv()
                                .getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        flyway =
                Flyway.configure()
                        .dataSource(source)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .cleanDisabled(false)
                        .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        gateway = new DurableMockGateway(jdbc, manager);
    }

    @AfterEach
    void removeOwnedSchema() {
        if (flyway != null) {
            flyway.clean();
        }
    }

    @DisplayName("승인 응답이 유실되고 호출자가 롤백해도 새 게이트웨이는 기존 결과 하나를 조회한다")
    @Test
    void lostSuccessResponseSurvivesCallerRollbackAndNewGatewayReadsOneOriginalResult() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                status ->
                                                        gateway.authorize(
                                                                11L,
                                                                PaymentScenario
                                                                        .SUCCESS_RESPONSE_LOST)))
                .isInstanceOf(MockPaymentResultUnknownException.class)
                .extracting("reason")
                .isEqualTo(Reason.RESPONSE_LOST_AFTER_RESULT);
        Timestamp approvedAt = authorizedAt(11L);

        var recreated = new DurableMockGateway(jdbc, manager);
        assertThat(recreated.find(11L)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recreated.authorize(11L, PaymentScenario.SUCCESS_RESPONSE_LOST))
                .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recreated.authorize(11L, PaymentScenario.INSTANT_FAIL))
                .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(authorizedAt(11L)).isEqualTo(approvedAt);
        assertThat(resultCount()).isEqualTo(1);
    }

    @DisplayName("실패 응답이 유실되면 확정 실패를 조회할 때까지 전송 결과를 미확정으로 유지한다")
    @Test
    void lostFailureResponseIsAnUnknownDeliveryUntilLookupReturnsAuthoritativeFailure() {
        assertThatThrownBy(() -> gateway.authorize(12L, PaymentScenario.FAILURE_RESPONSE_LOST))
                .isInstanceOf(MockPaymentResultUnknownException.class);
        var recreated = new DurableMockGateway(jdbc, manager);
        assertThat(recreated.find(12L)).isEqualTo(PaymentStatus.FAILED);
        assertThat(recreated.authorize(12L, PaymentScenario.INSTANT_SUCCESS))
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(resultCount()).isEqualTo(1);
    }

    @DisplayName("결과 저장 전 장애와 반복 조회는 실패 결과나 승인을 새로 생성하지 않는다")
    @Test
    void unavailableBeforeResultAndRepeatedQueriesDoNotCreateFailureOrAuthorization() {
        assertThatThrownBy(() -> gateway.authorize(13L, PaymentScenario.UNAVAILABLE_BEFORE_RESULT))
                .isInstanceOf(MockPaymentResultUnknownException.class)
                .extracting("reason")
                .isEqualTo(Reason.UNAVAILABLE_BEFORE_RESULT);
        for (int i = 0; i < 3; i++) {
            assertThat(gateway.find(13L)).isNull();
        }
        assertThat(resultCount()).isZero();

        // 자동 재시도와 별개로 결제 제공자의 최종 결과가 나중에 도착하는 상황을 재현한다.
        assertThat(gateway.authorize(13L, PaymentScenario.INSTANT_SUCCESS))
                .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(gateway.find(13L)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(gateway.authorize(13L, PaymentScenario.UNAVAILABLE_BEFORE_RESULT))
                .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(resultCount()).isEqualTo(1);
    }

    @DisplayName("같은 요청의 동시 응답 유실에도 게이트웨이 결과를 여러 개 생성하지 않는다")
    @Test
    void sameRequestConcurrentResponsesCanBeLostOnceWithoutCreatingMultipleResults()
            throws Exception {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var outcomes = new ConcurrentLinkedQueue<PaymentStatus>();
        var lost = new ConcurrentLinkedQueue<MockPaymentResultUnknownException>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                outcomes.add(
                                        gateway.authorize(
                                                14L, PaymentScenario.SUCCESS_RESPONSE_LOST));
                            } catch (MockPaymentResultUnknownException unknown) {
                                lost.add(unknown);
                            } catch (Throwable failure) {
                                failures.add(failure);
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).isEmpty();
        assertThat(lost).hasSize(1);
        assertThat(outcomes).hasSize(3).containsOnly(PaymentStatus.SUCCESS);
        assertThat(resultCount()).isEqualTo(1);
        assertThat(new DurableMockGateway(jdbc, manager).find(14L))
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    private Timestamp authorizedAt(long id) {
        return jdbc.queryForObject(
                "SELECT authorized_at FROM mock_gateway_result WHERE attempt_id=?",
                Timestamp.class,
                id);
    }

    private int resultCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result", Integer.class);
    }
}
