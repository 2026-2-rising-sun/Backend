package com.shoppinglive.live.broadcast.infrastructure;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {
    Optional<Broadcast> findByRequestKey(String requestKey);

    @Query("SELECT b FROM Broadcast b ORDER BY b.createdAt DESC, b.id DESC")
    Page<Broadcast> findAllOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT b FROM Broadcast b WHERE b.status = 'PREPARING' ORDER BY b.scheduledAt ASC, b.id ASC")
    Page<Broadcast> findAllPreparing(Pageable pageable);

    @Query("SELECT b FROM Broadcast b WHERE b.status = 'LIVE' ORDER BY b.startedAt DESC, b.id DESC")
    Page<Broadcast> findAllLive(Pageable pageable);

    @Query("SELECT b FROM Broadcast b WHERE b.status = 'ENDED' ORDER BY b.endedAt DESC, b.id DESC")
    Page<Broadcast> findAllEnded(Pageable pageable);
}
