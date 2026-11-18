package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.payment.ledger.entity.LedgerAccount;
import com.shoppinglive.payment.ledger.entity.LedgerEntry;
import com.shoppinglive.payment.ledger.entity.LedgerJournal;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.repository.LedgerEntryRepository;
import com.shoppinglive.payment.ledger.repository.LedgerJournalRepository;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentSellerAmount;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import com.shoppinglive.payment.payment.entity.PgCall;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.entity.PgCallOutcome;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PaymentSellerAmountRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 엔티티 5개가 V4 컬럼에 저장되고 다시 읽혀 같은 값이 되는지 실제 PostgreSQL에서 확인한다. */
@SpringBootTest(
        properties = {"spring.profiles.active=test", "shoppinglive.outbox.relay.enabled=false"})
@Tag("postgres")
@Transactional
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_POSTGRES_URL", matches = ".+")
class PaymentEntityPersistenceTest {
    static final String SCHEMA = "payment_entity_" + UUID.randomUUID().toString().replace("-", "");
    static final String BUYER = "5c2e8f1a-0000-4000-8000-6d4c0b9e7a13";
    static final String SELLER = "7a1c0000-0000-4000-8000-000000000001";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        String url = System.getenv("PAYMENT_TEST_POSTGRES_URL");
        p.add(
                "spring.datasource.url",
                () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> System.getenv("PAYMENT_TEST_POSTGRES_USER"));
        p.add("spring.datasource.password", () -> System.getenv("PAYMENT_TEST_POSTGRES_PASSWORD"));
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("payment.core-service-token", () -> "payment-entity-postgres-test-credential-32");
        p.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired EntityManager em;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSellerAmountRepository sellerAmounts;
    @Autowired PgCallRepository pgCalls;
    @Autowired LedgerJournalRepository journals;
    @Autowired LedgerEntryRepository entries;

    @Test
    @DisplayName("새 결제는 CREATED·첫 결제창·환불 0으로 저장되고 생성·변경 시각이 채워진다")
    void paymentRoundTrip() {
        Instant expiresAt =
                Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        Long id =
                payments.save(new Payment("OD-20261110-K7Q2M9XA", BUYER, 30000L, expiresAt))
                        .getId();
        em.flush();
        em.clear();

        Payment found = payments.findByOrderRef("OD-20261110-K7Q2M9XA").orElseThrow();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getBuyerId()).isEqualTo(BUYER);
        assertThat(found.getAmount()).isEqualTo(30000L);
        assertThat(found.getRefundedAmount()).isZero();
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(found.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(found.getPgProvider()).isEqualTo("TOSS");
        assertThat(found.getCheckoutSeq()).isEqualTo(1);
        assertThat(found.getPgOrderId()).isEqualTo("OD-20261110-K7Q2M9XA-1");
        assertThat(found.getPgPaymentKey()).isNull();
        assertThat(found.getApprovedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("판매자별 금액·PG 호출 기록·분개가 결제 ID·분개 ID로 저장되고 다시 읽힌다")
    void childrenRoundTrip() {
        Long paymentId =
                payments.save(
                                new Payment(
                                        "OD-20261110-CHILD001",
                                        BUYER,
                                        30000L,
                                        Instant.now().plusSeconds(600)))
                        .getId();
        sellerAmounts.save(
                new PaymentSellerAmount(
                        paymentId,
                        "OD-20261110-CHILD001-S1",
                        SELLER,
                        20000L,
                        new BigDecimal("0.1000")));
        sellerAmounts.save(
                new PaymentSellerAmount(
                        paymentId,
                        "OD-20261110-CHILD001-S2",
                        SELLER,
                        10000L,
                        new BigDecimal("0.0550")));
        pgCalls.save(
                new PgCall(paymentId, PgCallKind.CONFIRM, "OD-20261110-CHILD001-1", "confirm-key"));
        journals.insertIfAbsent(LedgerRefType.PAYMENT_APPROVED.name(), paymentId.toString());
        Long journalId =
                journals.findByRefTypeAndRefId(LedgerRefType.PAYMENT_APPROVED, paymentId.toString())
                        .orElseThrow()
                        .getId();
        entries.save(new LedgerEntry(journalId, LedgerAccount.PG_CUSTODY, 30000L, 0L, null));
        entries.save(new LedgerEntry(journalId, LedgerAccount.BUYER_DEPOSIT, 0L, 30000L, null));
        em.flush();
        em.clear();

        assertThat(sellerAmounts.findByPaymentId(paymentId))
                .extracting(
                        PaymentSellerAmount::getSellerOrderNumber, PaymentSellerAmount::getAmount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("OD-20261110-CHILD001-S1", 20000L),
                        org.assertj.core.groups.Tuple.tuple("OD-20261110-CHILD001-S2", 10000L));
        assertThat(sellerAmounts.findByPaymentId(paymentId))
                .extracting(PaymentSellerAmount::getCommissionRate)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("0.1"), new BigDecimal("0.055"));

        PgCall call =
                pgCalls.findAll().stream()
                        .filter(c -> c.getPaymentId().equals(paymentId))
                        .findFirst()
                        .orElseThrow();
        assertThat(call.getKind()).isEqualTo(PgCallKind.CONFIRM);
        assertThat(call.getOutcome()).isEqualTo(PgCallOutcome.UNKNOWN);
        assertThat(call.getIdempotencyKey()).isEqualTo("confirm-key");
        assertThat(call.getRequestedAt()).isNotNull();
        assertThat(call.getRespondedAt()).isNull();

        LedgerJournal journal =
                journals.findByRefTypeAndRefId(LedgerRefType.PAYMENT_APPROVED, paymentId.toString())
                        .orElseThrow();
        assertThat(journal.getId()).isEqualTo(journalId);
        assertThat(journal.getCreatedAt()).isNotNull();
        assertThat(entries.findByJournalId(journalId))
                .extracting(LedgerEntry::getAccount, LedgerEntry::getDebit, LedgerEntry::getCredit)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(LedgerAccount.PG_CUSTODY, 30000L, 0L),
                        org.assertj.core.groups.Tuple.tuple(
                                LedgerAccount.BUYER_DEPOSIT, 0L, 30000L));
    }
}
