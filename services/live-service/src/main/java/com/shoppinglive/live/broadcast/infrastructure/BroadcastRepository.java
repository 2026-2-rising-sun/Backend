package com.shoppinglive.live.broadcast.infrastructure;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {
    Optional<Broadcast> findByRequestKey(String requestKey);
}
