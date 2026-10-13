package com.shoppinglive.live.promotion.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.contracts.events.EventEnvelope;
import com.shoppinglive.contracts.events.LiveBroadcastEventContract;
import com.shoppinglive.contracts.events.LiveBroadcastEventType;
import com.shoppinglive.contracts.events.LiveBroadcastPromotionClosedPayload;
import com.shoppinglive.contracts.events.LiveBroadcastPromotionOpenedPayload;
import com.shoppinglive.live.promotion.domain.BroadcastPromotion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 프로모션 상태 전이를 live.broadcast.v1 토픽에 outbox 로 남긴다. 호출 전에 반드시 flush 가 끝나
 * {@code promotionVersion} 이 전이 후 값이어야 한다. 봉투·토픽·key 는 방송 이벤트와 공유한다.
 */
@Component
public class PromotionEventPublisher {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() { };

    private final EventOutbox outbox;
    private final ObjectMapper objectMapper;

    public PromotionEventPublisher(final EventOutbox outbox, final ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    public void opened(final BroadcastPromotion promotion) {
        publish(LiveBroadcastEventType.LiveBroadcastPromotionOpened, promotion, promotion.getOpenedAt(),
            new LiveBroadcastPromotionOpenedPayload(
                promotion.getBroadcastId(), promotion.getId(), promotion.getVersion(),
                promotion.getType(), promotion.getTitle(), decodeAttributes(promotion.getAttributes()),
                promotion.getOpenedAt(), promotion.getClosesAt()));
    }

    public void closed(final BroadcastPromotion promotion) {
        publish(LiveBroadcastEventType.LiveBroadcastPromotionClosed, promotion, promotion.getClosedAt(),
            new LiveBroadcastPromotionClosedPayload(
                promotion.getBroadcastId(), promotion.getId(), promotion.getVersion(),
                promotion.getType(), promotion.getCloseReason().name(),
                promotion.getOpenedAt(), promotion.getClosedAt()));
    }

    private void publish(final LiveBroadcastEventType type, final BroadcastPromotion promotion,
                         final Instant occurredAt, final Object payload) {
        outbox.append(LiveBroadcastEventContract.TOPIC_NAME, EventEnvelope.create(
            type.name(), LiveBroadcastEventContract.SCHEMA_VERSION,
            LiveBroadcastEventContract.SOURCE,
            LiveBroadcastEventContract.messageKey(promotion.getBroadcastId()), occurredAt, payload));
    }

    private Map<String, Object> decodeAttributes(final String json) {
        try {
            return objectMapper.readValue(json, ATTRIBUTES_TYPE);
        } catch (IOException exception) {
            // 생성·수정 시 ObjectMapper 로 직렬화한 값이라, 역직렬화 실패는 데이터 손상으로 봐야 한다.
            throw new UncheckedIOException(exception);
        }
    }
}
