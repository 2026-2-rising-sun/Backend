package com.shoppinglive.payment.ledger.repository;

import com.shoppinglive.payment.ledger.entity.LedgerJournal;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerJournalRepository extends JpaRepository<LedgerJournal, Long> {
    Optional<LedgerJournal> findByRefTypeAndRefId(LedgerRefType refType, String refId);
}
