package com.shoppinglive.payment.ledger.repository;

import com.shoppinglive.payment.ledger.entity.LedgerEntry;
import java.util.List;
import org.springframework.data.repository.Repository;

/** 분개 줄은 넣고 읽기만 한다. {@code JpaRepository}의 삭제 메서드를 물려받지 않으려고 {@link Repository}를 쓴다(LED-05). */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {
    LedgerEntry save(LedgerEntry entry);

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    List<LedgerEntry> findByJournalId(Long journalId);
}
