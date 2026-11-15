package com.shoppinglive.payment.ledger.repository;

import com.shoppinglive.payment.ledger.entity.LedgerJournal;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 분개 머리는 넣고 읽기만 한다. {@code JpaRepository}의 삭제 메서드를 물려받지 않으려고 {@link Repository}를 쓴다(LED-05). */
public interface LedgerJournalRepository extends Repository<LedgerJournal, Long> {
    LedgerJournal save(LedgerJournal journal);

    Optional<LedgerJournal> findByRefTypeAndRefId(LedgerRefType refType, String refId);

    /**
     * 같은 사건이 없을 때만 머리를 넣는다. 0이면 이미 기록됐다(LED-03). UNIQUE 예외를 잡지 않는 이유: PostgreSQL은 위반 시 호출자 트랜잭션 전체가
     * 중단된다. 네이티브 INSERT는 auditing을 거치지 않으므로 시각은 DB {@code now()}다.
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO ledger_journal (ref_type, ref_id, created_at)
                    VALUES (:refType, :refId, now())
                    ON CONFLICT DO NOTHING
                    """,
            nativeQuery = true)
    int insertIfAbsent(@Param("refType") String refType, @Param("refId") String refId);
}
