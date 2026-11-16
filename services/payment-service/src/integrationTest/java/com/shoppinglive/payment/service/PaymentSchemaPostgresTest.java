package com.shoppinglive.payment.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("postgres")
@Tag("migration")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentSchemaPostgresTest {
    Flyway flyway;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        String schema = "payment_schema_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        var source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        System.getenv("PAYMENT_TEST_POSTGRES_USER"),
                        System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        flyway =
                Flyway.configure()
                        .locations("classpath:db/payment-migration")
                        .dataSource(source)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .cleanDisabled(false)
                        .load();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
    }

    @AfterEach
    void cleanup() {
        if (flyway != null) flyway.clean();
    }

    void insert(long id, String status, String scenario) {
        jdbc.update(
                "INSERT INTO payment_attempt(id,request_id,payment_id,order_id,payment_group_id,reference_number,amount,scenario,status,requested_at,scheduled_resolve_at,created_at,updated_at) VALUES (?,?,?,999,999,'PG',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id,
                UUID.randomUUID().toString(),
                id,
                scenario,
                status);
    }

    @Test
    @DisplayName("Payment 상태는 기존 호환 상태와 UNKNOWN을 보존하고 알 수 없는 상태는 거절한다")
    void allKnownStatusesAndUnknownAreAccepted() {
        int id = 0;
        for (String status :
                List.of("PENDING", "PROCESSING", "UNKNOWN", "SUCCESS", "FAILED", "TIMEOUT"))
            insert(++id, status, "INSTANT_SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class))
                .isEqualTo(6);
        assertThatThrownBy(() -> insert(7, "INVALID", "INSTANT_SUCCESS"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("전송 결과 유실·조회 불가를 포함한 기존 시나리오와 재시도·lease 제약을 유지한다")
    void recoveryScenariosAndMetadataConstraints() {
        int id = 0;
        for (String scenario :
                List.of(
                        "INSTANT_SUCCESS",
                        "INSTANT_FAIL",
                        "DELAYED_SUCCESS",
                        "DELAYED_FAIL",
                        "SUCCESS_RESPONSE_LOST",
                        "FAILURE_RESPONSE_LOST",
                        "UNAVAILABLE_BEFORE_RESULT")) insert(++id, "PROCESSING", scenario);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Integer.class))
                .isEqualTo(7);
        assertThatThrownBy(() -> insert(8, "PROCESSING", "INVALID"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_attempt SET retry_count=4 WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE payment_attempt SET lease_token='orphan' WHERE id=1"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("주문·묶음은 외부 ID 값으로 보관하고 Payment 내부 환불만 원결제를 참조한다")
    void paymentSchemaHasNoCrossServiceForeignKey() {
        insert(1, "SUCCESS", "INSTANT_SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name IN ('orders','payment_group','sales_info','refund_request')",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name IN ('payment_attempt','refund_execution','mock_gateway_result','mock_refund_result','event_outbox')",
                                Integer.class))
                .isEqualTo(5);
    }
}
