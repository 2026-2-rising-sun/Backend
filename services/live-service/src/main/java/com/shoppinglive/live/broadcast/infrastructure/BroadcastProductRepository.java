package com.shoppinglive.live.broadcast.infrastructure;

import com.shoppinglive.live.broadcast.domain.BroadcastProduct;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastProductRepository extends JpaRepository<BroadcastProduct, Long> {
    List<BroadcastProduct> findByBroadcastIdOrderByPositionAsc(Long broadcastId);

    Optional<BroadcastProduct> findByBroadcastIdAndProductId(Long broadcastId, Long productId);
}
