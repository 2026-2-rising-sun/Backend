package com.shoppinglive.payment;

import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.payment.dto.CreatePaymentRequest;
import com.shoppinglive.payment.payment.dto.SellerAmountRequest;
import com.shoppinglive.payment.payment.service.ApprovalService;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import com.shoppinglive.payment.payment.service.PaymentOutcomeService;
import com.shoppinglive.payment.payment.service.PaymentService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 승인 처리를 실제 PostgreSQL·V4 스키마에서 돌린다. 토스는 테스트 대역이다. 두 테스트 클래스가 같은 컨텍스트·스키마를 쓴다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
abstract class ApprovalPostgresTestSupport {
    static final String SCHEMA = "payment_approval_" + UUID.randomUUID().toString().replace("-", "");
    static final String BUYER = "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13";
    static final long AMOUNT = 30000;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.datasource.hikari.maximum-pool-size", () -> 20);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("payment.core-service-token", () -> "payment-approval-postgres-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired PaymentService payments;
    @Autowired ApprovalService approvals;
    @Autowired PaymentOutcomeService outcomes;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TossPaymentsClient toss;
    @MockitoSpyBean EventOutbox outbox;

    /** CREATED 결제(30000원, 결제창 1회차)를 만들고 승인 요청을 돌려준다. */
    ApproveCommand newPayment() {
        String orderRef = "OD-" + UUID.randomUUID().toString().substring(0, 13);
        payments.create(
                new CreatePaymentRequest(
                        orderRef,
                        UUID.fromString(BUYER),
                        AMOUNT,
                        OffsetDateTime.now().plusMinutes(10),
                        List.of(
                                new SellerAmountRequest(
                                        orderRef + "-S1",
                                        UUID.randomUUID(),
                                        AMOUNT,
                                        new BigDecimal("0.1000")))));
        return new ApproveCommand(orderRef, BUYER, "tgen_" + orderRef, orderRef + "-1", AMOUNT);
    }

    static TossPayment done(ApproveCommand c) {
        return new TossPayment(
                c.paymentKey(),
                c.pgOrderId(),
                "DONE",
                c.amount(),
                OffsetDateTime.parse("2026-11-10T14:02:11+09:00"),
                true);
    }

    Map<String, Object> payment(String orderRef) {
        return jdbc.queryForMap("SELECT * FROM payment WHERE order_ref = ?", orderRef);
    }

    long paymentId(String orderRef) {
        return jdbc.queryForObject(
                "SELECT id FROM payment WHERE order_ref = ?", Long.class, orderRef);
    }

    String status(String orderRef) {
        return (String) payment(orderRef).get("status");
    }

    /** "종류:결과" 목록, id 순. */
    List<String> pgCalls(String orderRef) {
        return jdbc.queryForList(
                "SELECT c.kind || ':' || c.outcome FROM pg_call c JOIN payment p ON p.id = c.payment_id"
                        + " WHERE p.order_ref = ? ORDER BY c.id",
                String.class,
                orderRef);
    }

    /** "계정:차변:대변" 목록. */
    List<String> approvalEntries(String orderRef) {
        return jdbc.queryForList(
                "SELECT e.account || ':' || e.debit || ':' || e.credit FROM ledger_entry e"
                        + " JOIN ledger_journal j ON j.id = e.journal_id"
                        + " WHERE j.ref_type = 'PAYMENT_APPROVED' AND j.ref_id = ? ORDER BY e.id",
                String.class,
                String.valueOf(paymentId(orderRef)));
    }

    int journals(String orderRef) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal WHERE ref_type = 'PAYMENT_APPROVED' AND ref_id = ?",
                Integer.class,
                String.valueOf(paymentId(orderRef)));
    }

    List<Map<String, Object>> outboxRows(String orderRef) {
        return jdbc.queryForList(
                "SELECT event_type, topic, message_key, payload FROM event_outbox WHERE message_key = ?",
                orderRef);
    }
}
