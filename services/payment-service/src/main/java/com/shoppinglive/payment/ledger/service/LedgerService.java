package com.shoppinglive.payment.ledger.service;

import com.shoppinglive.payment.ledger.entity.LedgerEntry;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.repository.LedgerEntryRepository;
import com.shoppinglive.payment.ledger.repository.LedgerJournalRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 분개를 남기는 유일한 곳. 고치거나 지우는 메서드는 두지 않는다. 정정은 반대 분개로 한다(LED-05). */
@Service
public class LedgerService {
    private final LedgerJournalRepository journals;
    private final LedgerEntryRepository entries;

    public LedgerService(LedgerJournalRepository journals, LedgerEntryRepository entries) {
        this.journals = journals;
        this.entries = entries;
    }

    /**
     * 사건 (종류, ID)의 분개를 호출자 트랜잭션 안에서 남긴다(LED-04). 상태 변경이 롤백되면 분개도 사라진다. 이미 기록된 사건이면 줄을 넣지 않고
     * false다(LED-03). 검사에 걸리면 DB에 닿기 전에 {@link IllegalArgumentException}이다(LED-01·02).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(LedgerRefType refType, String refId, List<Journal.Line> lines) {
        Journal journal = new Journal(refType, refId, lines);
        if (journals.insertIfAbsent(refType.name(), refId) == 0) {
            return false;
        }
        Long journalId = journals.findByRefTypeAndRefId(refType, refId).orElseThrow().getId();
        entries.saveAll(
                journal.lines().stream()
                        .map(
                                l ->
                                        new LedgerEntry(
                                                journalId,
                                                l.account(),
                                                l.debit(),
                                                l.credit(),
                                                l.sellerId()))
                        .toList());
        return true;
    }
}
