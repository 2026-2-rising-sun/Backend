package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundPurpose;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import com.shoppinglive.payment.refund.service.RefundResultService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** #370·#386이 함께 쓰는 실행 기반(결과 기록·재처리 조회·선점·refund_item)을 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class RefundResultPostgresTest extends RefundPostgresTestSupport {
    static final String SCHEMA =
            "payment_refund_result_" + UUID.randomUUID().toString().replace("-", "");

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
        p.add("payment.core-service-token", () -> "payment-refund-result-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired RefundResultService results;
    @Autowired RefundRepository refunds;

    @Test
    @DisplayName(
            "[REF-EXEC-02][REF-EVENT-01] 성공 결과는 조건부 UPDATE와 같은 트랜잭션 outbox로 남고, 재수신은 같은 payload를 다시 보낸다")
    void recordsSucceededAndRepublishes() throws Exception {
        String orderRef = newOrderRef();
        ObjectNode request = request(orderRef, newPayment(orderRef), "USER_REFUND");
        Refund refund = intake.receive(request).orElseThrow();

        assertThat(results.record(refund.getId(), RefundStatus.SUCCEEDED, 4000, null, "tx-1"))
                .isTrue();
        // 이미 확정된 고객 환불은 다시 바꾸지 않는다
        assertThat(results.record(refund.getId(), RefundStatus.SUCCEEDED, 4000, null, "tx-1"))
                .isFalse();
        intake.receive(copy(request));

        Map<String, Object> row = refund(request.get("refundRequestId").asText());
        assertThat(row)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("result_version", 1)
                .containsEntry("refunded_amount", 4000L)
                .containsEntry("pg_transaction_key", "tx-1");
        assertThat(row.get("resolved_at")).isNotNull();
        assertThat(row.get("next_attempt_at")).isNull();
        List<JsonNode> events = results(orderRef);
        assertThat(events).hasSize(2);
        JsonNode payload = events.get(0).get("payload");
        assertThat(payload.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(payload.get("refundedAmount").longValue()).isEqualTo(4000);
        assertThat(payload.get("failureCode").isNull()).isTrue();
        assertThat(payload.get("nextActionAt").isNull()).isTrue();
        assertThat(payload.get("executionId").asText())
                .isEqualTo(row.get("execution_id").toString());
        assertThat(events.get(1).get("payload")).isEqualTo(payload);
    }

    @Test
    @DisplayName(
            "[REF-LATE-02] 시스템 보상은 FAILED 뒤 더 높은 버전으로 NO_CHARGE를 남기고 executionId는 그대로다. 고객 환불의 FAILED는 바꾸지 않는다")
    void compensationMayResolveAfterFailure() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        Refund compensation =
                intake.receive(request(orderRef, paymentId, "SYSTEM_COMPENSATION")).orElseThrow();
        Refund user = intake.receive(request(orderRef, paymentId, "USER_REFUND")).orElseThrow();

        assertThat(
                        results.record(
                                compensation.getId(), RefundStatus.FAILED, 0, "PG_DECLINED", null))
                .isTrue();
        assertThat(
                        results.record(
                                compensation.getId(), RefundStatus.NO_CHARGE, 0, "NO_CHARGE", null))
                .isTrue();
        assertThat(results.record(user.getId(), RefundStatus.FAILED, 0, "PG_DECLINED", null))
                .isTrue();
        assertThat(results.record(user.getId(), RefundStatus.SUCCEEDED, 4000, null, "tx-2"))
                .isFalse();

        List<JsonNode> events = results(orderRef);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).at("/payload/resultVersion").intValue()).isEqualTo(1);
        assertThat(events.get(1).at("/payload/resultVersion").intValue()).isEqualTo(2);
        assertThat(events.get(1).at("/payload/status").asText()).isEqualTo("NO_CHARGE");
        assertThat(events.get(1).at("/payload/executionId"))
                .isEqualTo(events.get(0).at("/payload/executionId"));
        // 진행 중이 아니게 된 보상은 결제당 1건 제약에서 빠진다
        assertThat(intake.receive(request(orderRef, paymentId, "SYSTEM_COMPENSATION"))).isPresent();
    }

    @Test
    @DisplayName("[REF-LATE-02] 접수에서 실패한 보상(결제 없음·ID 없음·불일치·진행 중 보상)은 돈이 움직일 수 없어 성공·미승인으로 바꾸지 않는다")
    void intakeFailureCannotBecomeResolved() throws Exception {
        String missingRef = newOrderRef();
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        intake.receive(request(orderRef, paymentId, "SYSTEM_COMPENSATION")).orElseThrow();
        List<ObjectNode> failed =
                List.of(
                        request(missingRef, 7001L, "SYSTEM_COMPENSATION"),
                        request(orderRef, paymentId + 1000, "SYSTEM_COMPENSATION"),
                        request(orderRef, paymentId, "SYSTEM_COMPENSATION"));

        for (ObjectNode request : failed) {
            assertThat(intake.receive(request)).isEmpty();
            long id =
                    refunds.findByRefundRequestId(request.get("refundRequestId").asText())
                            .orElseThrow()
                            .getId();
            assertThat(results.record(id, RefundStatus.SUCCEEDED, 4000, null, "tx-" + id))
                    .isFalse();
            assertThat(results.record(id, RefundStatus.NO_CHARGE, 0, "NO_CHARGE", null)).isFalse();
            assertThat(refund(request.get("refundRequestId").asText()))
                    .containsEntry("status", "FAILED")
                    .containsEntry("result_version", 1);
        }
    }

    @Test
    @DisplayName("[REF-EVENT-01] Core가 거부할 결과(성공 금액 불일치·고객 환불 NO_CHARGE·사유 없는 실패)는 기록하지 않는다")
    void rejectsResultsCoreWouldReject() {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        long user =
                intake.receive(request(orderRef, paymentId, "USER_REFUND")).orElseThrow().getId();

        assertThatThrownBy(() -> results.record(user, RefundStatus.SUCCEEDED, 3999, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> results.record(user, RefundStatus.NO_CHARGE, 0, "NO_CHARGE", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> results.record(user, RefundStatus.FAILED, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> results.record(user, RefundStatus.UNKNOWN, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(refund(refunds.findById(user).orElseThrow().getRefundRequestId()))
                .containsEntry("status", "PROCESSING");
    }

    @Test
    @DisplayName("[REF-EXEC-01] 재처리 조회는 목적별로 시각이 지난 미완료 환불만 집고, 선점은 한 번만 이긴다")
    void findsDueRefundsByPurposeAndClaimsOnce() {
        String orderRef = newOrderRef();
        long paymentId = newPayment(orderRef);
        long user =
                intake.receive(request(orderRef, paymentId, "USER_REFUND")).orElseThrow().getId();
        long system =
                intake.receive(request(orderRef, paymentId, "SYSTEM_COMPENSATION"))
                        .orElseThrow()
                        .getId();
        Instant now = Instant.now().plusSeconds(1);

        // 접수 때 next_attempt_at을 넣어, 커밋 뒤 실행 전에 죽어도 재처리가 집는다
        assertThat(refunds.findDue(RefundPurpose.USER_REFUND.name(), now, 100))
                .extracting(Refund::getId)
                .contains(user)
                .doesNotContain(system);
        assertThat(refunds.findDue(RefundPurpose.SYSTEM_COMPENSATION.name(), now, 100))
                .extracting(Refund::getId)
                .contains(system)
                .doesNotContain(user);

        assertThat(refunds.claim(user, now, now.plusSeconds(60))).isEqualTo(1);
        assertThat(refunds.claim(user, now, now.plusSeconds(60))).isZero();
        assertThat(refunds.findDue(RefundPurpose.USER_REFUND.name(), now, 100))
                .extracting(Refund::getId)
                .doesNotContain(user);
        assertThat(refund(refunds.findById(user).orElseThrow().getRefundRequestId()))
                .containsEntry("attempt_count", 1);
    }

    @Test
    @DisplayName("[REF-EXEC-02] refund_item은 환불 안에서 판매자 주문당 한 줄이고 금액은 0보다 크다")
    void refundItemConstraints() {
        String orderRef = newOrderRef();
        long id =
                intake.receive(request(orderRef, newPayment(orderRef), "USER_REFUND"))
                        .orElseThrow()
                        .getId();
        String insert =
                "INSERT INTO refund_item (refund_id, seller_order_number, amount) VALUES (?, ?, ?)";
        jdbc.update(insert, id, orderRef + "-S1", 4000);

        assertThatThrownBy(() -> jdbc.update(insert, id, orderRef + "-S1", 1000))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, id, orderRef + "-S2", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
