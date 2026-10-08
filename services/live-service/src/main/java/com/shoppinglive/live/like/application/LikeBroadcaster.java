package com.shoppinglive.live.like.application;

import com.shoppinglive.live.stream.application.BroadcastStreamRegistry;
import com.shoppinglive.live.stream.application.StreamEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Poll committed PostgreSQL versions; cancellations must publish decreasing totals too. */
@Component
public class LikeBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(LikeBroadcaster.class);
    private final BroadcastStreamRegistry registry;
    private final LikeService likes;
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

    public LikeBroadcaster(BroadcastStreamRegistry registry, LikeService likes) {
        this.registry = registry;
        this.likes = likes;
    }

    @Scheduled(fixedDelayString = "${live.likes.publish-interval:1s}")
    void publishChanged() {
        final var connected = registry.broadcastIds();
        lastSent.keySet().retainAll(connected);
        try {
            for (final var total : likes.totals(connected)) {
                final Long previous = lastSent.put(total.broadcastId(), total.version());
                if (previous == null || previous != total.version())
                    registry.publish(total.broadcastId(), StreamEvent.of("likes.updated", total));
            }
        } catch (RuntimeException e) {
            log.debug("like totals not published: cause={}", e.toString());
        }
    }
}
