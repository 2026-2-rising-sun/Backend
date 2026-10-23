package com.shoppinglive.live.broadcast.repository;

import com.shoppinglive.live.broadcast.entity.BroadcastChat;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastChatRepository extends JpaRepository<BroadcastChat, Long> {
    List<BroadcastChat> findByBroadcastIdOrderByCreatedAtDescIdDesc(Long broadcastId, Limit limit);

    Optional<BroadcastChat> findByIdAndBroadcastId(Long id, Long broadcastId);

    /** idx_broadcast_chat_recent(broadcast_id, created_at DESC, id DESC) 순서와 같다. */
    List<BroadcastChat> findByBroadcastIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(
            Long broadcastId, Instant from, Limit limit);
}
