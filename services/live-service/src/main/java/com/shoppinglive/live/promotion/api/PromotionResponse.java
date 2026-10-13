package com.shoppinglive.live.promotion.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.promotion.domain.BroadcastPromotion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;

/** 관리용 응답. attributes 는 저장된 정규화 JSON 을 다시 Map 으로 돌려준다. */
public record PromotionResponse(
        long id,
        long broadcastId,
        String type,
        String title,
        Map<String, Object> attributes,
        String status,
        Integer durationSeconds,
        Instant openedAt,
        Instant closesAt,
        Instant closedAt,
        String closeReason,
        long version) {

    private static final TypeReference<Map<String, Object>> ATTR_TYPE = new TypeReference<>() { };

    public static PromotionResponse from(final BroadcastPromotion promotion,
                                         final ObjectMapper mapper) {
        return new PromotionResponse(
            promotion.getId(),
            promotion.getBroadcastId(),
            promotion.getType(),
            promotion.getTitle(),
            decode(promotion.getAttributes(), mapper),
            promotion.getStatus().name(),
            promotion.getDurationSeconds(),
            promotion.getOpenedAt(),
            promotion.getClosesAt(),
            promotion.getClosedAt(),
            promotion.getCloseReason() == null ? null : promotion.getCloseReason().name(),
            promotion.getVersion());
    }

    private static Map<String, Object> decode(final String json, final ObjectMapper mapper) {
        try {
            return mapper.readValue(json, ATTR_TYPE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
