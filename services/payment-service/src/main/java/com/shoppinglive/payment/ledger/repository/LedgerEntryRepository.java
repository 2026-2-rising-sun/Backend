package com.shoppinglive.payment.ledger.repository;

import com.shoppinglive.payment.ledger.entity.LedgerEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    List<LedgerEntry> findByJournalId(Long journalId);
}
