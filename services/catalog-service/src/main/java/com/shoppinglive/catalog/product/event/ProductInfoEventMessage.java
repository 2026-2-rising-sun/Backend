package com.shoppinglive.catalog.product.event;

import com.fasterxml.jackson.databind.*;
import com.shoppinglive.catalog.product.entity.ProductCategory;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;

/** 상품 전체 snapshot을 독립된 소비 DTO로 검증한다. 최초 버전 0도 유효하다. */
public record ProductInfoEventMessage(
        long productId,
        String name,
        String description,
        ProductCategory category,
        long mainImageId,
        String mainImageUrl,
        String sellerId,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public static final String TOPIC = "commerce.product-info.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ProductInfoEventMessage parse(String key, String raw) {
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || !root.isObject() || integer(root, "schemaVersion", 1) != 1)
                throw new IllegalArgumentException("지원하지 않는 이벤트 봉투입니다.");
            JsonNode p = root.path("payload");
            long id = integer(p, "productId", 1);
            var envelope =
                    new EventEnvelope<>(
                            text(root, "eventId", 36),
                            text(root, "eventType", 100),
                            1,
                            text(root, "source", 100),
                            text(root, "aggregateId", 100),
                            Instant.parse(text(root, "occurredAt", 100)),
                            p);
            if (!"commercecore-service".equals(envelope.source())
                    || !"PRODUCT_INFO_CHANGED".equals(envelope.eventType())
                    || !Long.toString(id).equals(key)
                    || !key.equals(envelope.aggregateId()))
                throw new IllegalArgumentException("상품 이벤트 식별자가 일치하지 않습니다.");
            String seller = p.path("sellerId").isNull() ? null : text(p, "sellerId", 36);
            ProductCategory category =
                    p.path("category").isNull()
                            ? null
                            : ProductCategory.valueOf(text(p, "category", 32));
            return new ProductInfoEventMessage(
                    id,
                    text(p, "name", 100),
                    text(p, "description", 2000),
                    category,
                    integer(p, "mainImageId", 1),
                    text(p, "mainImageUrl", 2048),
                    seller,
                    integer(p, "version", 0),
                    Instant.parse(text(p, "createdAt", 100)),
                    Instant.parse(text(p, "updatedAt", 100)));
        } catch (Exception failure) {
            throw new InvalidMessageException("상품 snapshot 형식이 올바르지 않습니다.", failure);
        }
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
            throw new IllegalArgumentException(field + " 값이 올바르지 않습니다.");
        return value.textValue();
    }

    private static long integer(JsonNode node, String field, long min) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min)
            throw new IllegalArgumentException(field + " 값이 올바르지 않습니다.");
        return value.longValue();
    }

    public static final class InvalidMessageException extends RuntimeException {
        public InvalidMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
