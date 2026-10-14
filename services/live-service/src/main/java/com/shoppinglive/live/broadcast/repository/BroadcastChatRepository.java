package com.shoppinglive.live.broadcast.repository;

import com.shoppinglive.live.broadcast.entity.BroadcastChat;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastChatRepository extends JpaRepository<BroadcastChat, Long> {
    List<BroadcastChat> findByBroadcastIdOrderByCreatedAtDescIdDesc(Long broadcastId, Limit limit);
}
