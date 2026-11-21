package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.service.RefundSettlement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 토스 취소 확인 뒤의 결제·환불·분개 반영을 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "shoppinglive.outbox.relay.enabled=false",
            "payment.recovery.enabled=false"
        })
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class RefundSettlementPostgresTest extends RefundPostgresTestSupport {
    static final String SCHEMA =
            "payment_refund_settle_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        p.add("payment.core-service-token", () -> "payment-refund-settle-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired RefundSettlement settlement;

    private long approvedPayment(String orderRef) {
        long id = newPayment(orderRef);
        jdbc.update(
                "UPDATE payment SET status = 'APPROVED', partial_cancelable = true WHERE id = ?",
                id);
        return id;
    }

    private static ObjectNode withAmount(ObjectNode request, long amount) throws Exception {
        request.put("amount", amount);
        ObjectNode seller = (ObjectNode) request.get("items").get(0);
        seller.put("amount", amount);
        ((ObjectNode) seller.get("orderItems").get(0)).put("amount", amount);
        return (ObjectNode) copy(request);
    }

    private int journalLines(long refundId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entry e JOIN ledger_journal j ON e.journal_id = j.id"
                        + " WHERE j.ref_type = 'REFUND_SUCCEEDED' AND j.ref_id = ?",
                Integer.class,
                String.valueOf(refundId));
    }

    @Test
    @DisplayName("[REF-EXEC-02][LED-REFUND-01] 부분 환불은 결제·항목·분개·결과를 함께 남기고 다시 불러도 한 번만 반영한다")
    void settlesPartialOnce() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approvedPayment(orderRef);
        Refund refund = intake.receive(request(orderRef, paymentId, "USER_REFUND")).orElseThrow();

        assertThat(settlement.settle(refund.getId(), "tx-" + refund.getId())).isTrue();
        assertThat(settlement.settle(refund.getId(), "tx-" + refund.getId())).isFalse();

        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", paymentId))
                .containsEntry("status", "PARTIALLY_REFUNDED")
                .containsEntry("refunded_amount", 4000L);
        assertThat(refund(refund.getRefundRequestId()))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("refunded_amount", 4000L);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT seller_order_number, amount FROM refund_item WHERE refund_id = ?",
                                refund.getId()))
                .containsEntry("seller_order_number", orderRef + "-S1")
                .containsEntry("amount", 4000L);
        assertThat(journalLines(refund.getId())).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(e.debit) FROM ledger_entry e JOIN ledger_journal j ON e.journal_id = j.id"
                                        + " WHERE j.ref_id = ? AND j.ref_type = 'REFUND_SUCCEEDED'"
                                        + " AND e.account = 'BUYER_DEPOSIT'",
                                Long.class,
                                String.valueOf(refund.getId())))
                .isEqualTo(4000L);
        assertThat(results(orderRef)).hasSize(1);
    }

    @Test
    @DisplayName("[REF-EXEC-02] 남은 금액을 모두 환불하면 결제는 REFUNDED다")
    void settlesFull() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approvedPayment(orderRef);
        Refund refund =
                intake.receive(withAmount(request(orderRef, paymentId, "USER_REFUND"), AMOUNT))
                        .orElseThrow();

        assertThat(settlement.settle(refund.getId(), "tx-" + refund.getId())).isTrue();

        assertThat(jdbc.queryForMap("SELECT * FROM payment WHERE id = ?", paymentId))
                .containsEntry("status", "REFUNDED")
                .containsEntry("refunded_amount", AMOUNT);
    }

    @Test
    @DisplayName("[REF-EXEC-02] 결제에 반영할 수 없으면 성공 결과·항목·분개를 모두 롤백한다")
    void rollsBackWhenPaymentCannotTakeRefund() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approvedPayment(orderRef);
        Refund refund = intake.receive(request(orderRef, paymentId, "USER_REFUND")).orElseThrow();
        jdbc.update("UPDATE payment SET refunded_amount = ? WHERE id = ?", AMOUNT - 1, paymentId);

        assertThatThrownBy(() -> settlement.settle(refund.getId(), "tx-" + refund.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(refund(refund.getRefundRequestId()))
                .containsEntry("status", "PROCESSING")
                .containsEntry("result_version", 0);
        assertThat(results(orderRef)).isEmpty();
        assertThat(journalLines(refund.getId())).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refund_item WHERE refund_id = ?",
                                Integer.class,
                                refund.getId()))
                .isZero();
    }

    @Test
    @DisplayName("[REF-EXEC-01][REF-EXEC-02] 같은 환불을 동시에 반영해도 돈은 한 번만 움직인다")
    void concurrentSettleMovesMoneyOnce() throws Exception {
        String orderRef = newOrderRef();
        long paymentId = approvedPayment(orderRef);
        Refund refund = intake.receive(request(orderRef, paymentId, "USER_REFUND")).orElseThrow();
        Callable<Boolean> call = () -> settlement.settle(refund.getId(), "tx-" + refund.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> calls = pool.invokeAll(List.of(call, call));
            assertThat(List.of(calls.get(0).get(), calls.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT refunded_amount FROM payment WHERE id = ?",
                                Long.class,
                                paymentId))
                .isEqualTo(4000L);
        assertThat(journalLines(refund.getId())).isEqualTo(2);
        assertThat(results(orderRef)).hasSize(1);
    }
}
