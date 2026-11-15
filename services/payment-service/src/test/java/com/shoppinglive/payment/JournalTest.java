package com.shoppinglive.payment;

import static com.shoppinglive.payment.ledger.entity.LedgerAccount.BUYER_DEPOSIT;
import static com.shoppinglive.payment.ledger.entity.LedgerAccount.PG_CUSTODY;
import static com.shoppinglive.payment.ledger.entity.LedgerAccount.SELLER_PAYABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.service.Journal;
import com.shoppinglive.payment.ledger.service.Journal.Line;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JournalTest {
    static final String SELLER = "7a1c0000-0000-4000-8000-000000000001";

    static Journal journal(Line... lines) {
        return new Journal(LedgerRefType.PAYMENT_APPROVED, "1001", List.of(lines));
    }

    @Test
    @DisplayName("[LED-01] 차변 합과 대변 합이 같으면 분개를 만든다")
    void balancedJournal() {
        Journal journal =
                journal(
                        new Line(PG_CUSTODY, 30000, 0, null),
                        new Line(BUYER_DEPOSIT, 0, 20000, null),
                        new Line(BUYER_DEPOSIT, 0, 10000, null));

        assertThat(journal.lines()).hasSize(3);
    }

    @Test
    @DisplayName("[LED-01] 차변 합과 대변 합이 다르면 거절한다")
    void unbalancedJournal() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                journal(
                                        new Line(PG_CUSTODY, 30000, 0, null),
                                        new Line(BUYER_DEPOSIT, 0, 29999, null)));
    }

    @Test
    @DisplayName("[LED-01] 줄이 없거나 하나뿐이면 거절한다")
    void tooFewLines() {
        assertThatIllegalArgumentException().isThrownBy(() -> journal());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> journal(new Line(PG_CUSTODY, 30000, 0, null)));
    }

    @Test
    @DisplayName("[LED-01] 합계가 long 범위를 넘으면 균형이 맞아 보여도 거절한다")
    void overflowIsRejected() {
        // 차변 MAX + MAX는 long에서 -2로 돈다. 넘침을 막지 않으면 우연히 대변 합과 같아질 수 있다
        assertThatThrownBy(
                        () ->
                                journal(
                                        new Line(PG_CUSTODY, Long.MAX_VALUE, 0, null),
                                        new Line(PG_CUSTODY, Long.MAX_VALUE, 0, null),
                                        new Line(BUYER_DEPOSIT, 0, 1, null)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("[LED-02] 한 줄에 차변과 대변이 둘 다 있거나 둘 다 0이면 거절한다")
    void oneSidePerLine() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                journal(
                                        new Line(PG_CUSTODY, 100, 100, null),
                                        new Line(BUYER_DEPOSIT, 0, 0, null)));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                journal(
                                        new Line(PG_CUSTODY, 30000, 0, null),
                                        new Line(BUYER_DEPOSIT, 0, 30000, null),
                                        new Line(BUYER_DEPOSIT, 0, 0, null)));
    }

    @Test
    @DisplayName("[LED-02] 음수 금액은 거절한다")
    void negativeAmount() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                journal(
                                        new Line(PG_CUSTODY, -30000, 0, null),
                                        new Line(BUYER_DEPOSIT, -30000, 0, null)));
    }

    @Test
    @DisplayName("[LED-02] 판매자 미지급금 줄에 판매자 ID가 없으면 거절하고, 있으면 만든다")
    void sellerPayableNeedsSeller() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                journal(
                                        new Line(BUYER_DEPOSIT, 30000, 0, null),
                                        new Line(SELLER_PAYABLE, 0, 30000, null)));

        assertThat(
                        journal(
                                        new Line(BUYER_DEPOSIT, 30000, 0, null),
                                        new Line(SELLER_PAYABLE, 0, 30000, SELLER))
                                .lines())
                .hasSize(2);
    }

    @Test
    @DisplayName("[LED-01] 사건 종류·ID와 계정이 없으면 거절한다")
    void requiredFields() {
        List<Line> lines =
                List.of(new Line(PG_CUSTODY, 1, 0, null), new Line(BUYER_DEPOSIT, 0, 1, null));
        assertThatThrownBy(() -> new Journal(null, "1", lines))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Journal(LedgerRefType.PAYMENT_APPROVED, null, lines))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                journal(
                                        new Line(null, 1, 0, null),
                                        new Line(BUYER_DEPOSIT, 0, 1, null)))
                .isInstanceOf(NullPointerException.class);
    }
}
