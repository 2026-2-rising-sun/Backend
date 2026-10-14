package com.shoppinglive.live.broadcast.service;

import com.shoppinglive.live.broadcast.entity.Broadcast;
import com.shoppinglive.live.stream.StreamEvent;
import com.shoppinglive.live.stream.StreamRelay;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 방송 종료가 커밋된 뒤에 호출한다. 여기서 실패해도 종료 성공은 바뀌지 않는다. 좋아요는 이미 DB에 저장되어 있다. 알림에 실패하면 재연결 시 409로 종료를 안다. */
@Component
public class BroadcastEndNotifier {

    private final StreamRelay relay;

    public BroadcastEndNotifier(final StreamRelay relay) {
        this.relay = relay;
    }

    public void ended(final Broadcast broadcast) {
        final long id = broadcast.getId();
        relay.publish(
                id,
                StreamEvent.of(
                        StreamRelay.BROADCAST_ENDED,
                        Map.of("broadcastId", id, "endedAt", broadcast.getEndedAt())));
    }
}
