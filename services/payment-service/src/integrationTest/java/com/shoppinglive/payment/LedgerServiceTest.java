package com.shoppinglive.payment;

import static com.shoppinglive.payment.ledger.entity.LedgerAccount.BUYER_DEPOSIT;
import static com.shoppinglive.payment.ledger.entity.LedgerAccount.PG_CUSTODY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.repository.LedgerEntryRepository;
import com.shoppinglive.payment.ledger.repository.LedgerJournalRepository;
import com.shoppinglive.payment.ledger.service.Journal.Line;
import com.shoppinglive.payment.ledger.service.LedgerService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** 원장 서비스가 H2(PostgreSQL 모드)에서 분개를 저장·거절하는지 본다. 동시 중복은 {@link LedgerServicePostgresTest}가 본다. */
class LedgerServiceTest extends PaymentApiTestSupport {
    @Autowired LedgerService ledger;
    @Autowired TransactionTemplate tx;

    static String newRefId() {
        return UUID.randomUUID().toString();
    }

    static List<Line> approval(long amount) {
        return List.of(
                new Line(PG_CUSTODY, amount, 0, null), new Line(BUYER_DEPOSIT, 0, amount, null));
    }

    int journalCount(String refId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_journal WHERE ref_id = ?", Integer.class, refId);
    }

    List<Map<String, Object>> entries(String refId) {
        return jdbc.queryForList(
                "SELECT e.account, e.debit, e.credit, e.seller_id FROM ledger_entry e"
                        + " JOIN ledger_journal j ON j.id = e.journal_id WHERE j.ref_id = ? ORDER BY e.id",
                refId);
    }

    @Test
    @DisplayName("[LED-01] 균형 맞는 분개는 머리 1행과 줄을 그대로 저장한다")
    void recordsBalancedJournal() {
        String refId = newRefId();

        Boolean recorded =
                tx.execute(
                        s -> ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000)));

        assertThat(recorded).isTrue();
        assertThat(journalCount(refId)).isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT ref_type, created_at FROM ledger_journal WHERE ref_id = ?",
                                refId))
                .containsEntry("REF_TYPE", "PAYMENT_APPROVED")
                .extractingByKey("CREATED_AT")
                .isNotNull();
        List<Map<String, Object>> rows = entries(refId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("ACCOUNT", "PG_CUSTODY");
        assertThat(((Number) rows.get(0).get("DEBIT")).longValue()).isEqualTo(30000L);
        assertThat(((Number) rows.get(0).get("CREDIT")).longValue()).isZero();
        assertThat(rows.get(1)).containsEntry("ACCOUNT", "BUYER_DEPOSIT");
        assertThat(((Number) rows.get(1).get("DEBIT")).longValue()).isZero();
        assertThat(((Number) rows.get(1).get("CREDIT")).longValue()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("[LED-01] 차변 합과 대변 합이 다르면 머리도 줄도 저장하지 않는다")
    void unbalancedJournalSavesNothing() {
        String refId = newRefId();
        List<Line> unbalanced =
                List.of(
                        new Line(PG_CUSTODY, 30000, 0, null),
                        new Line(BUYER_DEPOSIT, 0, 29999, null));

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        s ->
                                                ledger.record(
                                                        LedgerRefType.PAYMENT_APPROVED,
                                                        refId,
                                                        unbalanced)));

        assertThat(journalCount(refId)).isZero();
        assertThat(entries(refId)).isEmpty();
    }

    @Test
    @DisplayName("[LED-03] 같은 사건(ref_type, ref_id)을 두 번 기록하면 두 번째는 아무것도 하지 않는다")
    void duplicateEventIsIgnored() {
        String refId = newRefId();

        Boolean first =
                tx.execute(
                        s -> ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000)));
        Boolean second =
                tx.execute(
                        s -> ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(50000)));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(journalCount(refId)).isEqualTo(1);
        assertThat(entries(refId))
                .hasSize(2)
                .allSatisfy(
                        r ->
                                assertThat(
                                                ((Number) r.get("DEBIT")).longValue()
                                                        + ((Number) r.get("CREDIT")).longValue())
                                        .isEqualTo(30000L));
    }

    @Test
    @DisplayName("[LED-03] 사건 ID가 같아도 종류가 다르면 다른 분개다")
    void sameRefIdOtherTypeIsSeparate() {
        String refId = newRefId();

        tx.execute(s -> ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000)));
        Boolean refund =
                tx.execute(
                        s ->
                                ledger.record(
                                        LedgerRefType.REFUND_SUCCEEDED,
                                        refId,
                                        List.of(
                                                new Line(BUYER_DEPOSIT, 30000, 0, null),
                                                new Line(PG_CUSTODY, 0, 30000, null))));

        assertThat(refund).isTrue();
        assertThat(journalCount(refId)).isEqualTo(2);
        assertThat(entries(refId)).hasSize(4);
    }

    @Test
    @DisplayName("[LED-04] 호출자 트랜잭션 없이 부르면 거절하고 아무것도 저장하지 않는다")
    void requiresCallerTransaction() {
        String refId = newRefId();

        assertThatThrownBy(
                        () -> ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000)))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(journalCount(refId)).isZero();
    }

    @Test
    @DisplayName("[LED-04] 호출한 트랜잭션이 롤백되면 분개도 남지 않는다")
    void rollsBackWithCaller() {
        String refId = newRefId();

        tx.executeWithoutResult(
                s -> {
                    ledger.record(LedgerRefType.PAYMENT_APPROVED, refId, approval(30000));
                    s.setRollbackOnly();
                });

        assertThat(journalCount(refId)).isZero();
        assertThat(entries(refId)).isEmpty();
    }

    @Test
    @DisplayName("[LED-05] 원장 서비스와 리포지토리에 분개를 고치거나 지우는 메서드가 없다")
    void noUpdateOrDelete() {
        assertThat(
                        Arrays.stream(LedgerService.class.getDeclaredMethods())
                                .filter(m -> Modifier.isPublic(m.getModifiers()))
                                .map(Method::getName))
                .containsExactly("record");
        assertThat(
                        Stream.of(LedgerJournalRepository.class, LedgerEntryRepository.class)
                                .flatMap(c -> Arrays.stream(c.getMethods()))
                                .map(Method::getName))
                .noneMatch(
                        n ->
                                n.startsWith("delete")
                                        || n.startsWith("update")
                                        || n.startsWith("remove"));
    }
}
