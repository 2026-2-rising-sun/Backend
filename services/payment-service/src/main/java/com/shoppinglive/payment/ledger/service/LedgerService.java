package com.shoppinglive.payment.ledger.service;

import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {
    public boolean record(LedgerRefType refType, String refId, List<Journal.Line> lines) {
        throw new UnsupportedOperationException("Red: 아직 구현하지 않았다");
    }
}
