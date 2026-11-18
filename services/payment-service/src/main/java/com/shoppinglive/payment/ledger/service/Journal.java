package com.shoppinglive.payment.ledger.service;

import com.shoppinglive.payment.ledger.entity.LedgerAccount;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import java.util.List;
import java.util.Objects;

/**
 * 사건 하나의 분개. 만들 때 검사하므로 이 객체가 있으면 저장해도 되는 분개다. 사건 종류·ID(빈 값 거절)와 줄 2개 이상(LED-01), 줄마다 차변·대변 한쪽만 0보다
 * 크고 판매자 미지급금 줄에는 판매자 ID(LED-02), 차변 합 = 대변 합(LED-01, 넘침 거절). 어기면 {@link IllegalArgumentException},
 * null은 {@link NullPointerException}.
 */
public record Journal(LedgerRefType refType, String refId, List<Line> lines) {

    public Journal {
        Objects.requireNonNull(refType, "refType");
        Objects.requireNonNull(refId, "refId");
        if (refId.isBlank()) {
            throw new IllegalArgumentException("사건 ID가 비어 있다");
        }
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("분개는 줄이 2개 이상이어야 한다: " + lines.size());
        }
        long debit = 0;
        long credit = 0;
        try {
            for (Line line : lines) {
                // 넘침을 허용하면 큰 금액끼리 합이 돌아 균형이 맞아 보일 수 있다
                debit = Math.addExact(debit, line.debit());
                credit = Math.addExact(credit, line.credit());
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("분개 금액 합이 long 범위를 넘는다", e);
        }
        if (debit != credit) {
            throw new IllegalArgumentException("차변 합과 대변 합이 다르다: " + debit + " != " + credit);
        }
    }

    /** 분개 한 줄. DB CHECK({@code ck_ledger_side}, {@code ck_ledger_seller})와 같은 조건을 저장 전에 검사한다. */
    public record Line(LedgerAccount account, long debit, long credit, String sellerId) {
        public Line {
            Objects.requireNonNull(account, "account");
            if (!((debit > 0 && credit == 0) || (credit > 0 && debit == 0))) {
                throw new IllegalArgumentException(
                        "한 줄은 차변·대변 한쪽만 0보다 커야 한다: " + debit + "/" + credit);
            }
            if (sellerId != null && sellerId.isBlank()) {
                throw new IllegalArgumentException("판매자 ID가 비어 있다");
            }
            if (account == LedgerAccount.SELLER_PAYABLE && sellerId == null) {
                throw new IllegalArgumentException("판매자 미지급금 줄에는 판매자 ID가 있어야 한다");
            }
        }
    }
}
