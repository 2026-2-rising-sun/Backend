package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.payment.PaymentServiceApplication;
import com.shoppinglive.payment.service.*;
import java.util.*;
import javax.sql.DataSource;
import org.mockito.Mockito;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Core와 다른 컨텍스트·DataSource의 실제 Payment 서버를 HTTP로 연결하는 테스트 fixture. */
@TestConfiguration(proxyBeanMethods = false)
public class PaymentClientTestConfiguration {
    public static final String TOKEN = "core-payment-boundary-test-service-token-32";

    @Bean(destroyMethod = "close")
    BoundaryPaymentServer boundaryPaymentServer() {
        return new BoundaryPaymentServer();
    }

    @Bean
    @Primary
    PaymentClient boundaryPaymentClient(BoundaryPaymentServer server) {
        return server.client();
    }

    @Bean
    PaymentBoundaryReconciler paymentBoundaryReconciler(
            BoundaryPaymentServer server,
            PaymentDispatcher dispatcher,
            PaymentDeliveryStore store,
            JdbcTemplate jdbc) {
        return new PaymentBoundaryReconciler(
                server.context.getBean(PaymentDelayReconciler.class), dispatcher, store, jdbc);
    }

    @Bean
    RefundBoundaryTestDriver refundBoundaryTestDriver(
            PaymentDispatcher dispatcher,
            PaymentDeliveryStore deliveries,
            PaymentClient client,
            PaymentResultHandler handler,
            BoundaryPaymentServer server,
            JdbcTemplate jdbc) {
        return new RefundBoundaryTestDriver(
                jdbc,
                dispatcher,
                deliveries,
                client,
                handler,
                server.context.getBean(
                        com.shoppinglive.payment.service.RefundExecutionService.class));
    }

    @Bean
    com.shoppinglive.payment.service.RefundOutcomeService executionRefundOutcomes(
            BoundaryPaymentServer s) {
        return s.context.getBean(com.shoppinglive.payment.service.RefundOutcomeService.class);
    }

    @Bean
    PaymentDelayReconciler executionScanner(BoundaryPaymentServer s) {
        return s.context.getBean(PaymentDelayReconciler.class);
    }

    @Bean
    RefundBoundaryReconciler executionRefundScanner(
            RefundRecoveryStore recovery, RefundBoundaryTestDriver executions) {
        return new RefundBoundaryReconciler(recovery, executions);
    }

    @Bean
    com.shoppinglive.payment.service.PaymentService executionPaymentService(
            BoundaryPaymentServer s) {
        return s.context.getBean(com.shoppinglive.payment.service.PaymentService.class);
    }

    @Bean
    DurableMockGateway paymentGateway(BoundaryPaymentServer s) {
        return s.context.getBean(DurableMockGateway.class);
    }

    @Bean
    DurableMockRefundGateway paymentRefundGateway(BoundaryPaymentServer s) {
        return s.context.getBean(DurableMockRefundGateway.class);
    }

    @Bean
    PaymentRecoveryStore paymentRecovery(BoundaryPaymentServer s) {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                s.context.getBean(PaymentRecoveryStore.class));
    }

    @Bean
    RefundRecoveryStore paymentRefundRecovery(BoundaryPaymentServer s) {
        return s.context.getBean(RefundRecoveryStore.class);
    }

    @Bean
    MockPaymentEngine paymentEngine(BoundaryPaymentServer s) {
        return s.context.getBean(MockPaymentEngine.class);
    }

    @Bean
    RefundMockEngine paymentRefundEngine(BoundaryPaymentServer s) {
        return s.context.getBean(RefundMockEngine.class);
    }

    @Bean
    com.shoppinglive.payment.repository.PaymentAttemptJpaRepository executionAttempts(
            BoundaryPaymentServer s) {
        return s.context.getBean(
                com.shoppinglive.payment.repository.PaymentAttemptJpaRepository.class);
    }

    public static class BoundaryPaymentServer implements AutoCloseable {
        public final ConfigurableApplicationContext context;
        public final JdbcTemplate jdbc;
        final HttpPaymentClient http;

        public BoundaryPaymentServer() {
            String db = System.getenv("PAYMENT_TEST_POSTGRES_URL");
            String schema = "payment_boundary_" + UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> props = new HashMap<>();
            props.put("server.port", 0);
            props.put("server.tomcat.threads.min-spare", 1);
            props.put("server.tomcat.threads.max", 12);
            props.put("spring.application.name", "payment-boundary-fixture");
            props.put("spring.main.allow-bean-definition-overriding", true);
            props.put("payment.core-service-token", TOKEN);
            props.put("spring.profiles.active", "test,payment-boundary-fixture");
            props.put("payment.dev.payment-scenario.enabled", true);
            props.put(
                    "spring.config.location",
                    "optional:classpath:/payment-fixture-no-application.yml");
            props.put("spring.jpa.hibernate.ddl-auto", "validate");
            props.put("spring.jpa.open-in-view", false);
            props.put("spring.flyway.enabled", false);
            props.put("spring.sql.init.mode", "never");
            props.put("shoppinglive.outbox.relay.enabled", false);
            props.put("payment.execution.recovery-enabled", false);
            props.put("payment.refunds.execution-enabled", false);
            props.put("spring.kafka.bootstrap-servers", "localhost:1");
            props.put("management.endpoint.health.probes.enabled", true);
            if (db != null && !db.isBlank()) {
                var admin =
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                db,
                                System.getenv("PAYMENT_TEST_POSTGRES_USER"),
                                System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
                new JdbcTemplate(admin).execute("CREATE SCHEMA " + schema);
                props.put(
                        "spring.datasource.url",
                        db + (db.contains("?") ? "&" : "?") + "currentSchema=" + schema);
                props.put(
                        "spring.datasource.username", System.getenv("PAYMENT_TEST_POSTGRES_USER"));
                props.put(
                        "spring.datasource.password",
                        System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
            } else {
                props.put(
                        "spring.datasource.url",
                        "jdbc:h2:mem:" + schema + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
                props.put("spring.datasource.username", "sa");
                props.put("spring.datasource.password", "");
            }
            // 단일 Gradle 테스트 classpath에서도 서비스별 스키마를 명시적으로 고른다.
            PaymentFixtureOverrides.properties = props;
            context =
                    new SpringApplicationBuilder(
                                    PaymentServiceApplication.class, PaymentFixtureOverrides.class)
                            .properties(props)
                            .run();
            jdbc = context.getBean(JdbcTemplate.class);
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            http =
                    new HttpPaymentClient(
                            RestClient.builder()
                                    .baseUrl("http://localhost:" + port)
                                    .defaultHeader("X-Service-Token", TOKEN)
                                    .build());
        }

        public PaymentClient client() {
            return new PaymentClient() {
                public PaymentExecution start(PaymentCommand c) {
                    var ids =
                            jdbc.queryForList(
                                    "SELECT id FROM payment_attempt WHERE payment_id=?",
                                    Long.class,
                                    c.paymentId());
                    if (!ids.isEmpty())
                        context.getBean(com.shoppinglive.payment.service.PaymentService.class)
                                .resolvePayment(ids.getFirst());
                    return http.start(c);
                }

                public void closeOrder(long id, OrderClosureCommand command) {
                    http.closeOrder(id, command);
                }

                public PaymentExecution get(long id) {
                    return http.get(id);
                }

                public RefundExecution refund(RefundCommand c) {
                    return http.refund(c);
                }

                public RefundExecution refund(long id) {
                    return http.refund(id);
                }

                public void setScenario(
                        String ref,
                        com.shoppinglive.commercecore.payment.entity.PaymentScenario scenario) {
                    http.setScenario(ref, scenario);
                }

                public void clearScenario(String ref) {
                    http.clearScenario(ref);
                }
            };
        }

        public void reset() {
            Mockito.reset(
                    context.getBean(MockPaymentEngine.class),
                    context.getBean(RefundMockEngine.class),
                    context.getBean(DurableMockGateway.class),
                    context.getBean(DurableMockRefundGateway.class),
                    org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                            context.getBean(PaymentRecoveryStore.class)));
        }

        public void close() {
            context.close();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("payment-boundary-fixture")
    public static class PaymentFixtureOverrides {
        static Map<String, Object> properties;

        @Bean
        DataSource dataSource() throws Exception {
            var source =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            (String) properties.get("spring.datasource.url"),
                            (String) properties.get("spring.datasource.username"),
                            (String) properties.get("spring.datasource.password"));
            JdbcTemplate jdbc = new JdbcTemplate(source);
            boolean h2 = ((String) properties.get("spring.datasource.url")).contains(":h2:");
            if (h2)
                jdbc.execute(
                        "CREATE ALIAS IF NOT EXISTS CLOCK_TIMESTAMP FOR 'com.shoppinglive.commercecore.payment.service.PaymentClientTestConfiguration.databaseNow'");
            for (String name :
                    List.of(
                            "V1__payment_baseline.sql",
                            "V2__event_outbox.sql",
                            "V3__outbox_worker_claim.sql",
                            "V4__order_closure_compensation.sql")) {
                String sql =
                        new String(
                                Objects.requireNonNull(
                                                PaymentServiceApplication.class.getResourceAsStream(
                                                        "/db/payment-migration/" + name))
                                        .readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                if (h2)
                    sql =
                            sql.replaceAll(
                                            ",\\s*ADD COLUMN",
                                            "; ALTER TABLE event_outbox ADD COLUMN")
                                    .replaceAll(
                                            "(?m)(CREATE (?:UNIQUE )?INDEX [^;]+?) WHERE [^;]+;",
                                            "$1;");
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                                new org.springframework.core.io.ByteArrayResource(
                                        sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .execute(source);
            }
            return source;
        }

        @Bean
        MockPaymentEngine mockPaymentEngine() {
            return Mockito.mock(MockPaymentEngine.class);
        }

        @Bean
        RefundMockEngine refundMockEngine() {
            return Mockito.mock(RefundMockEngine.class);
        }

        @Bean
        DurableMockGateway durableMockGateway(
                JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx) {
            return Mockito.spy(new DurableMockGateway(jdbc, tx));
        }

        @Bean
        DurableMockRefundGateway durableMockRefundGateway(
                JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx) {
            return Mockito.spy(new DurableMockRefundGateway(jdbc, tx));
        }

        @Bean
        PaymentRecoveryStore paymentRecoveryStore(
                JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx) {
            return Mockito.spy(new PaymentRecoveryStore(jdbc, tx));
        }
    }

    public static java.sql.Timestamp databaseNow() {
        return java.sql.Timestamp.from(java.time.Instant.now());
    }
}
