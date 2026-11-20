package com.shoppinglive.catalog.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import java.util.Set;

/** 새 계약의 봉투와 각 타입의 필드를 엄격히 확인한다. */
public record CatalogSnapshotMessage(
        String eventId,
        String aggregateId,
        String eventType,
        long version,
        JsonNode payload,
        JsonNode envelope) {
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS);

    public static CatalogSnapshotMessage parse(String key, String raw) {
        try {
            JsonNode root = MAPPER.readTree(raw);
            fields(
                    root,
                    Set.of(
                            "eventId",
                            "eventType",
                            "schemaVersion",
                            "source",
                            "aggregateId",
                            "occurredAt",
                            "payload"));
            String type = text(root, "eventType", 100);
            if (number(root, "schemaVersion", 1) != 1
                    || !text(root, "source", 100).equals("commercecore-service"))
                throw new IllegalArgumentException("지원하지 않는 이벤트 봉투입니다.");
            JsonNode p = root.get("payload");
            new EventEnvelope<>(
                    text(root, "eventId", 36),
                    type,
                    1,
                    "commercecore-service",
                    text(root, "aggregateId", 100),
                    Instant.parse(text(root, "occurredAt", 100)),
                    p);
            boolean product = type.equals("ProductSnapshotChanged");
            boolean inventory = type.equals("InventorySnapshotChanged");
            if (!product && !inventory && !type.equals("SkuSnapshotChanged"))
                throw new IllegalArgumentException("지원하지 않는 이벤트 타입입니다.");
            String expected =
                    (product ? "product:" : "sku:") + number(p, product ? "productId" : "skuId", 1);
            if (!expected.equals(key) || !expected.equals(root.get("aggregateId").asText()))
                throw new IllegalArgumentException("이벤트 키가 일치하지 않습니다.");
            number(p, "productId", 1);
            long version = number(p, inventory ? "inventoryVersion" : "metadataVersion", 0);
            if (product) {
                fields(
                        p,
                        Set.of(
                                "productId",
                                "metadataVersion",
                                "name",
                                "description",
                                "category",
                                "mainImageId",
                                "mainImageUrl",
                                "sellerId",
                                "createdAt",
                                "updatedAt",
                                "saleEnabled",
                                "deletedAt",
                                "initialSkuCount"));
                text(p, "name", 100);
                text(p, "description", 2000);
                number(p, "mainImageId", 1);
                text(p, "mainImageUrl", 2048);
                nullableText(p, "sellerId", 36);
                if (!p.get("category").isNull()
                        && !Set.of("FASHION", "BEAUTY", "FOOD", "LIVING", "DIGITAL", "KIDS", "ETC")
                                .contains(text(p, "category", 32)))
                    throw new IllegalArgumentException("상품 분류가 올바르지 않습니다.");
                if (!p.get("initialSkuCount").isNull() && number(p, "initialSkuCount", 1) > 100)
                    throw new IllegalArgumentException("최초 SKU 수가 올바르지 않습니다.");
                if (!p.get("saleEnabled").isBoolean()
                        || (!p.get("deletedAt").isNull() && p.get("saleEnabled").booleanValue()))
                    throw new IllegalArgumentException("삭제 상품은 판매할 수 없습니다.");
            } else if (inventory) {
                fields(
                        p,
                        Set.of(
                                "skuId",
                                "productId",
                                "inventoryVersion",
                                "available",
                                "reserved",
                                "capturedAt"));
                if (number(p, "available", 0) > Integer.MAX_VALUE
                        || number(p, "reserved", 0) > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("재고 범위를 벗어났습니다.");
                Instant.parse(text(p, "capturedAt", 100));
            } else {
                fields(
                        p,
                        Set.of(
                                "skuId",
                                "productId",
                                "metadataVersion",
                                "productMetadataVersion",
                                "price",
                                "currency",
                                "optionLabel",
                                "productNameSnapshot",
                                "saleStatus",
                                "createdAt",
                                "updatedAt",
                                "deletedAt"));
                number(p, "productMetadataVersion", 0);
                number(p, "price", 1);
                nullableText(p, "optionLabel", 255);
                text(p, "productNameSnapshot", 255);
                if (!text(p, "currency", 3).equals("KRW")
                        || !Set.of("READY", "ON_SALE", "PRIVATE")
                                .contains(text(p, "saleStatus", 16)))
                    throw new IllegalArgumentException("SKU 판매정보가 올바르지 않습니다.");
            }
            if (!inventory) {
                Instant.parse(text(p, "createdAt", 100));
                Instant.parse(text(p, "updatedAt", 100));
                if (!p.get("deletedAt").isNull()) Instant.parse(text(p, "deletedAt", 100));
            }
            return new CatalogSnapshotMessage(
                    text(root, "eventId", 36), expected, type, version, p, root);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Catalog 스냅샷 형식이 올바르지 않습니다.", failure);
        }
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size())
            throw new IllegalArgumentException("이벤트 필드가 일치하지 않습니다.");
        for (String name : expected)
            if (!node.has(name)) throw new IllegalArgumentException("필수 필드가 없습니다: " + name);
    }

    private static String text(JsonNode node, String name, int max) {
        JsonNode v = node.path(name);
        if (!v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max)
            throw new IllegalArgumentException("문자열 필드가 올바르지 않습니다: " + name);
        return v.textValue();
    }

    private static void nullableText(JsonNode node, String name, int max) {
        if (!node.get(name).isNull()) text(node, name, max);
    }

    private static long number(JsonNode node, String name, long min) {
        JsonNode v = node.path(name);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < min)
            throw new IllegalArgumentException("정수 필드가 올바르지 않습니다: " + name);
        return v.longValue();
    }
}
