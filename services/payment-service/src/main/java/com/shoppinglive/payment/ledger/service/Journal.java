package com.shoppinglive.payment.ledger.service;

import com.shoppinglive.payment.ledger.entity.LedgerAccount;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import java.util.List;

public record Journal(LedgerRefType refType, String refId, List<Line> lines) {
    public record Line(LedgerAccount account, long debit, long credit, String sellerId) {}
}
