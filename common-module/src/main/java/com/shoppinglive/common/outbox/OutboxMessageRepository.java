package com.shoppinglive.common.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    /** 기록 순서(id)대로 읽어야 같은 key 의 이벤트 순서가 Kafka 에서도 유지된다. */
    List<OutboxMessage> findByPublishedAtIsNullOrderByIdAsc(Pageable pageable);

    @Transactional
    @Modifying
    @Query(
            "UPDATE OutboxMessage m SET m.publishedAt = :at, m.attempts = m.attempts + 1, "
                    + "m.lastError = null WHERE m.id = :id")
    int markPublished(@Param("id") long id, @Param("at") Instant at);

    @Transactional
    @Modifying
    @Query(
            "UPDATE OutboxMessage m SET m.attempts = m.attempts + 1, m.lastError = :error "
                    + "WHERE m.id = :id")
    int markFailed(@Param("id") long id, @Param("error") String error);
}
