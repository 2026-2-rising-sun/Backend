package com.shoppinglive.notification.broadcast.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.notification.event.InvalidEventMessageException;
import java.time.DateTimeException;
import java.time.Instant;

/**
 * 방송 이벤트 한 건의 봉투를 해석하고 검증한다.
 *
 * <p>발행 측의 공유 DTO 를 쓰지 않는 이유: 그 record 는 모르는 값 하나에도 생성자가 던져서 메시지 전체를 못 읽는다. 여기서는 필드를 직접 읽어 값마다 다르게
 * 처리한다. 특히 <b>모르는 {@code eventType} 은 오류가 아니라 무시</b> 대상이다.
 *
 * <p>검증 실패는 {@link InvalidEventMessageException} 으로 알리고, listener 가 그 메시지를 건너뛴다. 다시 읽어도 같은 결과라서
 * 재시도할 이유가 없다.
 */
record LiveBroadcastEventMessage(
        String eventId, String eventType, Instant occurredAt, JsonNode payload) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static LiveBroadcastEventMessage parse(String raw) {
        JsonNode root = readRoot(raw);
        if (!isSupportedSchemaVersion(root.get("schemaVersion"))) {
            throw invalid("지원하지 않는 schemaVersion입니다.");
        }
        if (!LiveBroadcastEventContract.SOURCE.equals(textOf(root, "source"))) {
            throw invalid("source는 " + LiveBroadcastEventContract.SOURCE + "여야 합니다.");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalid("payload가 없거나 JSON 객체가 아닙니다.");
        }
        return new LiveBroadcastEventMessage(
                textOf(root, "eventId"),
                textOf(root, "eventType"),
                instantOf(root, "occurredAt"),
                payload);
    }

    /**
     * 메시지 key 가 payload 의 방송 ID 와 같은지 확인한다.
     *
     * <p>key 는 파티션을 정한다. 어긋나면 같은 방송의 이벤트가 다른 파티션으로 흩어져 순서 보장이 깨진 것이므로, 저장하지 않고 건너뛰어야 한다.
     */
    void requireKeyMatches(String messageKey, long broadcastId) {
        if (!Long.toString(broadcastId).equals(messageKey)) {
            throw invalid("메시지 key와 broadcastId가 일치하지 않습니다.");
        }
    }

    long payloadLongAtLeast(String field, long min) {
        JsonNode value = payload.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < min) {
            throw invalid(field + "는 " + min + " 이상의 정수여야 합니다.");
        }
        return value.longValue();
    }

    String payloadText(String field, int maxLength) {
        String value = textOf(payload, field);
        if (value.length() > maxLength) {
            throw invalid(field + "는 " + maxLength + "자 이하여야 합니다.");
        }
        return value;
    }

    Instant payloadInstant(String field) {
        return instantOf(payload, field);
    }

    private static JsonNode readRoot(String raw) {
        if (raw == null) {
            throw invalid("메시지 값이 비어 있습니다.");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw invalid("JSON 형식이 올바르지 않습니다.");
        }
        if (root == null || !root.isObject()) {
            throw invalid("메시지는 JSON 객체여야 합니다.");
        }
        return root;
    }

    private static boolean isSupportedSchemaVersion(JsonNode node) {
        return node != null
                && node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() == LiveBroadcastEventContract.SCHEMA_VERSION;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + "가 없거나 비어 있습니다.");
        }
        return value.textValue();
    }

    private static Instant instantOf(JsonNode node, String field) {
        try {
            return Instant.parse(textOf(node, field));
        } catch (DateTimeException e) {
            throw invalid(field + "는 ISO-8601 시각이어야 합니다.");
        }
    }

    private static InvalidEventMessageException invalid(String reason) {
        return new InvalidEventMessageException(reason);
    }
}
