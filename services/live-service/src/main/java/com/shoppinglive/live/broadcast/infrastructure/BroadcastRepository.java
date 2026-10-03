package com.shoppinglive.live.broadcast.infrastructure;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {
    Optional<Broadcast> findByRequestKey(String requestKey);

    /** 좋아요 합계를 보관할 방송: 진행 중이거나 since 이후에 종료된 방송. */
    @Query("SELECT b.id FROM Broadcast b WHERE b.status = 'LIVE' OR (b.status = 'ENDED' AND b.endedAt > :since)")
    List<Long> findIdsForLikeSnapshot(Instant since);

    long countByStatus(BroadcastStatus status);

    @Query("SELECT b FROM Broadcast b ORDER BY b.createdAt DESC, b.id DESC")
    Page<Broadcast> findAllOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT b FROM Broadcast b WHERE b.status = 'PREPARING' ORDER BY b.scheduledAt ASC, b.id ASC")
    Page<Broadcast> findAllPreparing(Pageable pageable);

    @Query("SELECT b FROM Broadcast b WHERE b.status = 'LIVE' ORDER BY b.startedAt DESC, b.id DESC")
    Page<Broadcast> findAllLive(Pageable pageable);

    @Query("SELECT b FROM Broadcast b WHERE b.status = 'ENDED' ORDER BY b.endedAt DESC, b.id DESC")
    Page<Broadcast> findAllEnded(Pageable pageable);
}
