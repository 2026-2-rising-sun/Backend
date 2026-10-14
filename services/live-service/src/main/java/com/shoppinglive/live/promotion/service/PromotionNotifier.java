package com.shoppinglive.live.promotion.service;

import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import com.shoppinglive.live.stream.StreamEvent;
import com.shoppinglive.live.stream.StreamRelay;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 프로모션 열림·닫힘을 시청자 SSE 로 보낸다. Redis 발행이라 유실 가능하다는 전제고, 유실된 시청자는 공개 조회({@code GET
 * /v1/broadcasts/{id}/promotions})로 복구한다. 서비스 트랜잭션 커밋 뒤에 호출해야 "커밋 안 된 상태를 시청자가 먼저 보는" 사태가 없다.
 */
@Component
public class PromotionNotifier {

    public static final String OPENED = "promotion.opened";
    public static final String CLOSED = "promotion.closed";

    private final StreamRelay relay;

    public PromotionNotifier(final StreamRelay relay) {
        this.relay = relay;
    }

    public void opened(final BroadcastPromotion promotion) {
        relay.publish(promotion.getBroadcastId(), StreamEvent.of(OPENED, body(promotion)));
    }

    public void closed(final BroadcastPromotion promotion) {
        relay.publish(promotion.getBroadcastId(), StreamEvent.of(CLOSED, body(promotion)));
    }

    private Map<String, Object> body(final BroadcastPromotion promotion) {
        // SSE 는 외부 소비자용이 아니므로 Kafka payload 와 같은 봉투를 요구하지 않는다.
        // 시청자가 "지금 어떤 프로모션이 열렸고 언제까지인지" 알 수 있는 필드만 담는다.
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("promotionId", promotion.getId());
        body.put("type", promotion.getType());
        body.put("title", promotion.getTitle());
        body.put("status", promotion.getStatus().name());
        if (promotion.getOpenedAt() != null) body.put("openedAt", promotion.getOpenedAt());
        if (promotion.getClosesAt() != null) body.put("closesAt", promotion.getClosesAt());
        if (promotion.getClosedAt() != null) body.put("closedAt", promotion.getClosedAt());
        if (promotion.getCloseReason() != null)
            body.put("closeReason", promotion.getCloseReason().name());
        return body;
    }
}
