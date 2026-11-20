package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 환불 요청 접수를 실제 PostgreSQL·V5 스키마에서 확인한다. Kafka 없이 접수 서비스를 직접 부르고 outbox 행을 본다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class RefundIntakePostgresTest extends RefundPostgresTestSupport {
    static final String SCHEMA = "payment_refund_" + UUID.randomUUID().toString().replace("-", "");

    /** Core 결과 소비자(#378 KafkaRefundService.result)가 허용하는 payload 필드 전부. */
    static final Set<String> RESULT_FIELDS =
            Set.of(
                    "refundRequestId",
                    "orderRef",
                    "paymentId",
                    "purpose",
                    "reason",
                    "currency",
                    "amount",
                    "items",
                    "requestedAt",
                    "executionId",
                    "resultVersion",
                    "status",
                    "refundedAmount",
                    "resolvedAt",
                    "nextActionAt",
                    "failureCode");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        // 다른 Postgres 테스트 컨텍스트도 캐시돼 연결을 쥐고 있다. 기본 10개면 max_connections(100)를 넘는다
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        p.add("payment.core-service-token", () -> "payment-refund-postgres-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Test
    @DisplayName("[REF-ACCEPT-01] 유효한 요청은 원문과 함께 PROCESSING으로 저장하고, 실행이 없는 목적은 결과를 보내지 않는다")
    void acceptsWithoutResultUntilExecutionExists() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        ObjectNode user = request(orderRef, paymentId, "USER_REFUND");
        ObjectNode system = request(orderRef, paymentId, "SYSTEM_COMPENSATION");

        intake.receive(user);
        intake.receive(system);

        for (ObjectNode request : List.of(user, system)) {
            Map<String, Object> row = refund(request.get("refundRequestId").asText());
            assertThat(row.get("status")).isEqualTo("PROCESSING");
            assertThat(row.get("payment_id")).isEqualTo(paymentId);
            assertThat(row.get("execution_id")).isNotNull();
            assertThat(row.get("result_version")).isEqualTo(0);
            assertThat(row.get("result_payload")).isNull();
            assertThat(JSON.readTree((String) row.get("request_payload"))).isEqualTo(request);
        }
        // #370(USER_REFUND)·#386(SYSTEM_COMPENSATION)이 실행을 붙이기 전까지는 접수만 한다
        assertThat(results(orderRef)).isEmpty();
    }

    @Test
    @DisplayName(
            "[REF-ACCEPT-05][REF-EVENT-01] 결제가 없으면 돈을 움직이지 않고 요청 원문 그대로 FAILED(PAYMENT_NOT_FOUND)를 보낸다")
    void paymentNotFound(CapturedOutput output) throws Exception {
        String orderRef = newOrderRef();
        ObjectNode request = request(orderRef, 7001L, "USER_REFUND");

        intake.receive(request);

        JsonNode result = single(orderRef);
        assertCoreShape(result, request, "FAILED", "PAYMENT_NOT_FOUND");
        Map<String, Object> row = refund(request.get("refundRequestId").asText());
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("payment_id")).isNull();
        assertThat(result.at("/payload/executionId").asText())
                .isEqualTo(row.get("execution_id").toString());
        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains(request.get("refundRequestId").asText());
    }

    @Test
    @DisplayName(
            "[REF-ACCEPT-05] paymentId가 없거나 결제와 다르면 FAILED(PAYMENT_ID_REQUIRED·PAYMENT_MISMATCH)를 보낸다")
    void paymentIdMissingOrMismatched() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        ObjectNode missing = request(orderRef, null, "SYSTEM_COMPENSATION");
        ObjectNode mismatched = request(orderRef, paymentId + 1000, "USER_REFUND");

        intake.receive(missing);
        intake.receive(mismatched);

        List<JsonNode> results = results(orderRef);
        assertThat(results).hasSize(2);
        assertCoreShape(results.get(0), missing, "FAILED", "PAYMENT_ID_REQUIRED");
        // 요청에 없던 paymentId를 결과에 채우지 않는다
        assertThat(results.get(0).at("/payload").has("paymentId")).isFalse();
        assertCoreShape(results.get(1), mismatched, "FAILED", "PAYMENT_MISMATCH");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payment WHERE id = ?",
                                Long.class,
                                paymentId))
                .isZero();
    }

    @Test
    @DisplayName("[REF-ACCEPT-02] 같은 요청·같은 본문을 다시 받으면 저장한 결과를 같은 버전·같은 payload로 새 eventId에 다시 보낸다")
    void sameBodyRepublishesStoredResult() throws Exception {
        String orderRef = newOrderRef();
        ObjectNode request = request(orderRef, 7001L, "USER_REFUND");

        intake.receive(request);
        intake.receive(copy(request));

        List<JsonNode> results = results(orderRef);
        assertThat(results).hasSize(2);
        assertThat(results.get(1).get("payload")).isEqualTo(results.get(0).get("payload"));
        assertThat(results.get(1).get("eventId")).isNotEqualTo(results.get(0).get("eventId"));
        assertThat(refundCount(request.get("refundRequestId").asText())).isEqualTo(1);
    }

    @Test
    @DisplayName("[REF-ACCEPT-02] 결과가 아직 없는 요청을 다시 받으면 새 환불도 결과도 만들지 않는다")
    void sameBodyWithoutResultDoesNothing() throws Exception {
        String orderRef = newOrderRef();
        ObjectNode request = request(orderRef, newPayment(orderRef), "USER_REFUND");

        intake.receive(request);
        intake.receive(copy(request));

        assertThat(refundCount(request.get("refundRequestId").asText())).isEqualTo(1);
        assertThat(results(orderRef)).isEmpty();
    }

    @Test
    @DisplayName("[REF-ACCEPT-02] 같은 요청 ID에 다른 본문이 오면 충돌만 기록하고 결과를 보내지 않으며 원래 요청은 그대로 둔다")
    void differentBodyIsRecordedAsConflict(CapturedOutput output) throws Exception {
        String orderRef = newOrderRef();
        ObjectNode original = request(orderRef, 7001L, "USER_REFUND");
        intake.receive(original);
        Map<String, Object> before = refund(original.get("refundRequestId").asText());

        ObjectNode changed = (ObjectNode) copy(original);
        changed.put("amount", 3999);
        intake.receive(changed);

        assertThat(results(orderRef)).hasSize(1);
        assertThat(refund(original.get("refundRequestId").asText())).isEqualTo(before);
        Map<String, Object> conflict =
                jdbc.queryForMap(
                        "SELECT * FROM refund_request_conflict WHERE refund_request_id = ?",
                        original.get("refundRequestId").asText());
        assertThat(conflict.get("failure_code")).isEqualTo("REQUEST_CONFLICT");
        assertThat(JSON.readTree((String) conflict.get("request_payload"))).isEqualTo(changed);
        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("REQUEST_CONFLICT");
    }

    @Test
    @DisplayName("[REF-ACCEPT-06] 같은 결제에 진행 중인 시스템 보상이 있으면 새 보상은 FAILED(COMPENSATION_IN_PROGRESS)다")
    void secondCompensationFails(CapturedOutput output) throws Exception {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        ObjectNode first = request(orderRef, paymentId, "SYSTEM_COMPENSATION");
        ObjectNode second = request(orderRef, paymentId, "SYSTEM_COMPENSATION");

        intake.receive(first);
        intake.receive(second);

        assertThat(refund(first.get("refundRequestId").asText()).get("status"))
                .isEqualTo("PROCESSING");
        assertThat(refund(second.get("refundRequestId").asText()).get("status"))
                .isEqualTo("FAILED");
        assertCoreShape(single(orderRef), second, "FAILED", "COMPENSATION_IN_PROGRESS");
        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("COMPENSATION_IN_PROGRESS");
    }

    @Test
    @DisplayName("[REF-ACCEPT-06] DB는 결제당 진행 중 시스템 보상 2건을 거부한다")
    void databaseRejectsTwoActiveCompensations() {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        String insert =
                "INSERT INTO refund (refund_request_id, execution_id, payment_id, purpose, amount,"
                        + " request_payload, status, result_version, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'SYSTEM_COMPENSATION', 4000, '{}', ?, 0, now(), now())";
        jdbc.update(insert, "c-" + UUID.randomUUID(), UUID.randomUUID(), paymentId, "PROCESSING");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        insert,
                                        "c-" + UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        paymentId,
                                        "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 끝난 보상은 막지 않는다
        jdbc.update(insert, "c-" + UUID.randomUUID(), UUID.randomUUID(), paymentId, "FAILED");
    }

    @Test
    @DisplayName("[REF-ACCEPT-03] DB는 결제 환불액이 결제 금액을 넘는 것을 거부한다")
    void databaseRejectsOverRefund() {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        jdbc.update("UPDATE payment SET refunded_amount = amount WHERE id = ?", paymentId);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE payment SET refunded_amount = amount + 1 WHERE id = ?",
                                        paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private JsonNode single(String orderRef) throws Exception {
        List<JsonNode> results = results(orderRef);
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    /** #378 KafkaRefundService.result·applyLocked가 검사하는 것을 그대로 본다. */
    private static void assertCoreShape(
            JsonNode event, JsonNode request, String status, String failureCode) {
        assertThat(event.size()).isEqualTo(7);
        assertThat(event.get("eventType").asText()).isEqualTo("RefundResultRecorded");
        assertThat(event.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(event.get("source").asText()).isEqualTo("payment-service");
        assertThat(UUID.fromString(event.get("eventId").asText())).isNotNull();
        assertThat(event.get("aggregateId").asText()).isEqualTo(request.get("orderRef").asText());
        JsonNode payload = event.get("payload");
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        assertThat(RESULT_FIELDS).containsAll(fields);
        // 요청 필드는 고치지 않고 그대로다(requestedAt 나노초 포함)
        request.fields()
                .forEachRemaining(f -> assertThat(payload.get(f.getKey())).isEqualTo(f.getValue()));
        assertThat(UUID.fromString(payload.get("executionId").asText())).isNotNull();
        assertThat(payload.get("resultVersion").longValue()).isEqualTo(1);
        assertThat(payload.get("status").asText()).isEqualTo(status);
        assertThat(payload.get("refundedAmount").longValue()).isZero();
        assertThat(payload.get("resolvedAt").isTextual()).isTrue();
        assertThat(payload.has("nextActionAt")).isTrue();
        assertThat(payload.get("nextActionAt").isNull()).isTrue();
        assertThat(payload.get("failureCode").asText()).isEqualTo(failureCode);
    }
}
