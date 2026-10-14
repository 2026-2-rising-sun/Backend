package com.shoppinglive.live.promotion.repository;

import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.promotion.entity.PromotionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastPromotionRepository extends JpaRepository<BroadcastPromotion, Long> {
    Optional<BroadcastPromotion> findByRequestKey(String requestKey);

    Optional<BroadcastPromotion> findByIdAndBroadcastId(long id, long broadcastId);

    List<BroadcastPromotion> findByBroadcastIdOrderByIdAsc(long broadcastId);

    List<BroadcastPromotion> findByBroadcastIdAndStatusOrderByIdAsc(
            long broadcastId, PromotionStatus status);

    List<BroadcastPromotion> findByBroadcastIdAndStatusIn(
            long broadcastId, Collection<PromotionStatus> statuses);

    /** 만료 스케줄러가 쓴다. 지난 closes_at 중 아직 OPEN 인 것부터 최대 100 건. */
    List<BroadcastPromotion> findTop100ByStatusAndClosesAtLessThanEqualOrderByClosesAtAsc(
            PromotionStatus status, Instant now);
}
