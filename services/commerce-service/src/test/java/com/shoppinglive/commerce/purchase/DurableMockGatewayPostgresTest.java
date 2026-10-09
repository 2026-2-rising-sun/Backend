package com.shoppinglive.commerce.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.payments.application.MockPaymentResultUnknownException;
import com.shoppinglive.commerce.payments.application.MockPaymentResultUnknownException.Reason;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.purchase.application.DurableMockGateway;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

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
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_USER", "postgres"),
            System.getenv().getOrDefault("COMMERCE_TEST_POSTGRES_PASSWORD", "postgres"));
        flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).cleanDisabled(false).load();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        gateway = new DurableMockGateway(jdbc, manager);
    }

    @AfterEach
    void removeOwnedSchema() {
        if (flyway != null) flyway.clean();
    }

    @Test
    void lostSuccessResponseSurvivesCallerRollbackAndNewGatewayReadsOneOriginalResult() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
            gateway.authorize(11L, PaymentScenario.SUCCESS_RESPONSE_LOST)))
            .isInstanceOf(MockPaymentResultUnknownException.class)
            .extracting("reason").isEqualTo(Reason.RESPONSE_LOST_AFTER_RESULT);
        Timestamp approvedAt = authorizedAt(11L);

        var recreated = new DurableMockGateway(jdbc, manager);
        assertThat(recreated.find(11L)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recreated.authorize(11L, PaymentScenario.SUCCESS_RESPONSE_LOST)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recreated.authorize(11L, PaymentScenario.INSTANT_FAIL)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(authorizedAt(11L)).isEqualTo(approvedAt);
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void lostFailureResponseIsAnUnknownDeliveryUntilLookupReturnsAuthoritativeFailure() {
        assertThatThrownBy(() -> gateway.authorize(12L, PaymentScenario.FAILURE_RESPONSE_LOST))
            .isInstanceOf(MockPaymentResultUnknownException.class);
        var recreated = new DurableMockGateway(jdbc, manager);
        assertThat(recreated.find(12L)).isEqualTo(PaymentStatus.FAILED);
        assertThat(recreated.authorize(12L, PaymentScenario.INSTANT_SUCCESS)).isEqualTo(PaymentStatus.FAILED);
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void unavailableBeforeResultAndRepeatedQueriesDoNotCreateFailureOrAuthorization() {
        assertThatThrownBy(() -> gateway.authorize(13L, PaymentScenario.UNAVAILABLE_BEFORE_RESULT))
            .isInstanceOf(MockPaymentResultUnknownException.class)
            .extracting("reason").isEqualTo(Reason.UNAVAILABLE_BEFORE_RESULT);
        for (int i = 0; i < 3; i++) assertThat(gateway.find(13L)).isNull();
        assertThat(resultCount()).isZero();

        // Simulate a later authoritative provider result, independent of any automatic retry policy.
        assertThat(gateway.authorize(13L, PaymentScenario.INSTANT_SUCCESS)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(gateway.find(13L)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(gateway.authorize(13L, PaymentScenario.UNAVAILABLE_BEFORE_RESULT)).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void sameRequestConcurrentResponsesCanBeLostOnceWithoutCreatingMultipleResults() throws Exception {
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var outcomes = new ConcurrentLinkedQueue<PaymentStatus>();
        var lost = new ConcurrentLinkedQueue<MockPaymentResultUnknownException>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(gateway.authorize(14L, PaymentScenario.SUCCESS_RESPONSE_LOST));
                } catch (MockPaymentResultUnknownException unknown) {
                    lost.add(unknown);
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally { done.countDown(); }
            });
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(failures).isEmpty();
        assertThat(lost).hasSize(1);
        assertThat(outcomes).hasSize(3).containsOnly(PaymentStatus.SUCCESS);
        assertThat(resultCount()).isEqualTo(1);
        assertThat(new DurableMockGateway(jdbc, manager).find(14L)).isEqualTo(PaymentStatus.SUCCESS);
    }

    private Timestamp authorizedAt(long id) {
        return jdbc.queryForObject("SELECT authorized_at FROM mock_gateway_result WHERE attempt_id=?", Timestamp.class, id);
    }

    private int resultCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result", Integer.class);
    }
}
