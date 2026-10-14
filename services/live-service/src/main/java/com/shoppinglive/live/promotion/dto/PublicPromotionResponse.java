package com.shoppinglive.live.promotion.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.live.promotion.entity.BroadcastPromotion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Map;

/** 시청자용 응답. OPEN 상태인 프로모션 조회 결과만 노출한다. */
public record PublicPromotionResponse(
        long id,
        long broadcastId,
        String type,
        String title,
        Map<String, Object> attributes,
        Instant openedAt,
        Instant closesAt) {

    private static final TypeReference<Map<String, Object>> ATTR_TYPE = new TypeReference<>() {};

    public static PublicPromotionResponse from(
            final BroadcastPromotion promotion, final ObjectMapper mapper) {
        return new PublicPromotionResponse(
                promotion.getId(),
                promotion.getBroadcastId(),
                promotion.getType(),
                promotion.getTitle(),
                decode(promotion.getAttributes(), mapper),
                promotion.getOpenedAt(),
                promotion.getClosesAt());
    }

    private static Map<String, Object> decode(final String json, final ObjectMapper mapper) {
        try {
            return mapper.readValue(json, ATTR_TYPE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
