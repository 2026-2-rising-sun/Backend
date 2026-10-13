package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.contracts.events.EventEnvelope;
import com.shoppinglive.contracts.events.LiveBroadcastEndedPayload;
import com.shoppinglive.contracts.events.LiveBroadcastEventContract;
import com.shoppinglive.contracts.events.LiveBroadcastEventType;
import com.shoppinglive.contracts.events.LiveBroadcastStartedPayload;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 방송 상태 전이를 live.broadcast.v1 이벤트로 outbox 에 기록한다. 실제 전이가 일어난
 * 트랜잭션 안에서, flush 로 version 이 오른 뒤에 불러야 한다.
 * 새 방송 이벤트는 여기에 메서드 하나와 payload 하나를 더하면 된다.
 */
@Component
public class BroadcastEventPublisher {
    private final EventOutbox outbox;

    public BroadcastEventPublisher(final EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void started(final Broadcast broadcast, final List<Long> productIds) {
        publish(LiveBroadcastEventType.LiveBroadcastStarted, broadcast, broadcast.getStartedAt(),
            new LiveBroadcastStartedPayload(broadcast.getId(), broadcast.getVersion(),
                broadcast.getTitle(), broadcast.getScheduledAt(), broadcast.getStartedAt(),
                productIds));
    }

    public void ended(final Broadcast broadcast) {
        publish(LiveBroadcastEventType.LiveBroadcastEnded, broadcast, broadcast.getEndedAt(),
            new LiveBroadcastEndedPayload(broadcast.getId(), broadcast.getVersion(),
                broadcast.getStartedAt(), broadcast.getEndedAt()));
    }

    private void publish(final LiveBroadcastEventType type, final Broadcast broadcast,
                         final Instant occurredAt, final Object payload) {
        outbox.append(LiveBroadcastEventContract.TOPIC_NAME, EventEnvelope.create(
            type.name(), LiveBroadcastEventContract.SCHEMA_VERSION,
            LiveBroadcastEventContract.SOURCE,
            LiveBroadcastEventContract.messageKey(broadcast.getId()), occurredAt, payload));
    }
}
