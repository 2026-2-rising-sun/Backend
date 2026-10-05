package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.like.application.LikeSnapshotService;
import com.shoppinglive.live.stream.application.StreamEvent;
import com.shoppinglive.live.stream.application.StreamRelay;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 방송 종료가 커밋된 뒤에 호출한다. 여기서 실패해도 종료 성공은 바뀌지 않는다.
 * 보관에 실패하면 주기 작업이 다시 하고, 알림에 실패하면 시청자는 재연결 시 409 로 종료를 안다.
 */
@Component
public class BroadcastEndNotifier {
    private static final Logger log = LoggerFactory.getLogger(BroadcastEndNotifier.class);

    private final LikeSnapshotService snapshots;
    private final StreamRelay relay;

    public BroadcastEndNotifier(final LikeSnapshotService snapshots, final StreamRelay relay) {
        this.snapshots = snapshots;
        this.relay = relay;
    }

    public void ended(final Broadcast broadcast) {
        final long id = broadcast.getId();
        try {
            snapshots.snapshot(id);
        } catch (RuntimeException e) {
            log.warn("like snapshot at broadcast end failed, periodic snapshot will retry: broadcastId={} cause={}",
                id, e.toString());
        }
        relay.publish(id, StreamEvent.of(StreamRelay.BROADCAST_ENDED,
            Map.of("broadcastId", id, "endedAt", broadcast.getEndedAt())));
    }
}
