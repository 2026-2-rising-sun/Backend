package com.shoppinglive.notification.sales.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.notification.event.InvalidEventMessageException;
import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import java.time.DateTimeException;
import java.time.Instant;

/**
 * 판매정보 이벤트 한 건을 해석하고 검증한다.
 *
 * <p>발행 측의 공유 DTO 를 쓰지 않는 이유: 그 record 는 모르는 값 하나에도 생성자가 던져서 메시지 전체를 못 읽는다. 여기서는 알림이 쓰는 필드만 직접 읽는다.
 *
 * <p>검증 실패는 {@link InvalidEventMessageException} 으로 알리고, listener 가 그 메시지를 건너뛴다. 다시 읽어도 같은 결과라서
 * 재시도할 이유가 없다.
 *
 * @param occurredAt 알림 시각의 근거. 판매정보 payload 에는 시각이 없어서 봉투의 값을 쓴다
 */
record SalesInfoEventMessage(String eventId, Instant occurredAt, ProductSalesStatus status) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static SalesInfoEventMessage parse(String raw, String messageKey) {
        JsonNode root = readRoot(raw);
        if (!isSupportedSchemaVersion(root.get("schemaVersion"))) {
            throw invalid("unsupported schemaVersion");
        }
        if (!SalesInfoEventContract.SOURCE.equals(textOf(root, "source"))) {
            throw invalid("source must be " + SalesInfoEventContract.SOURCE);
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalid("payload is missing");
        }
        long salesId = longAtLeast(payload, "salesId", 1);
        // key 는 파티션을 정한다. 어긋나면 같은 판매정보의 이벤트가 흩어져 순서 보장이 깨진 것이다.
        if (!Long.toString(salesId).equals(messageKey)) {
            throw invalid("message key does not match salesId");
        }
        return new SalesInfoEventMessage(
                textOf(root, "eventId"),
                instantOf(root, "occurredAt"),
                new ProductSalesStatus(
                        longAtLeast(payload, "productId", 1),
                        salesId,
                        longAtLeast(payload, "snapshotVersion", 1),
                        statusOf(payload)));
    }

    /**
     * 상태 값은 받은 그대로 둔다. 참거짓으로 줄이면 품절과 판매 준비가 같은 값이 되어 재입고 판별이 틀린다.
     *
     * <p>모르는 값이 와도 거부하지 않는다. 아는 값이 아니면 재입고 판별에도 신청 거절에도 걸리지 않아서, Commerce 가 상태를 추가해도 이쪽 판단이 흔들리지
     * 않는다. 길이만 사본 컬럼에 맞는지 본다.
     */
    private static String statusOf(JsonNode payload) {
        String status = textOf(payload, "status");
        if (status.length() > SalesInfoEventContract.STATUS_MAX_LENGTH) {
            throw invalid(
                    "status must be at most "
                            + SalesInfoEventContract.STATUS_MAX_LENGTH
                            + " characters");
        }
        return status;
    }

    private static JsonNode readRoot(String raw) {
        if (raw == null) {
            throw invalid("message value is empty");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw invalid("broken JSON");
        }
        if (root == null || !root.isObject()) {
            throw invalid("message must be a JSON object");
        }
        return root;
    }

    private static boolean isSupportedSchemaVersion(JsonNode node) {
        return node != null
                && node.isIntegralNumber()
                && node.canConvertToInt()
                && node.intValue() == SalesInfoEventContract.SCHEMA_VERSION;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " is missing");
        }
        return value.textValue();
    }

    private static long longAtLeast(JsonNode node, String field, long min) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < min) {
            throw invalid(field + " must be an integer >= " + min);
        }
        return value.longValue();
    }

    private static Instant instantOf(JsonNode node, String field) {
        try {
            return Instant.parse(textOf(node, field));
        } catch (DateTimeException e) {
            throw invalid(field + " must be an ISO-8601 instant");
        }
    }

    private static InvalidEventMessageException invalid(String reason) {
        return new InvalidEventMessageException(reason);
    }
}
