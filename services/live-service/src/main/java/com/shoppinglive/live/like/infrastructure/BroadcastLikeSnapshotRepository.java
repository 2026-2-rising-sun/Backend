package com.shoppinglive.live.like.infrastructure;

import com.shoppinglive.live.like.domain.BroadcastLikeSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastLikeSnapshotRepository extends JpaRepository<BroadcastLikeSnapshot, Long> {
}
