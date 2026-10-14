package com.shoppinglive.live.broadcast.repository;

import com.shoppinglive.live.broadcast.entity.BroadcastProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastProductRepository extends JpaRepository<BroadcastProduct, Long> {
    List<BroadcastProduct> findByBroadcastIdOrderByPositionAsc(Long broadcastId);

    Optional<BroadcastProduct> findByBroadcastIdAndProductId(Long broadcastId, Long productId);
}
