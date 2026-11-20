package com.shoppinglive.payment.refund.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code commerce.refund.requests.v1} 메시지를 읽어 요청 payload를 고치지 않고 돌려준다. 계약 원본은 {@code
 * contracts/events/commerce-refund-requests.yaml}.
 *
 * <p>결과를 만들 수 없는 경우(봉투·키·요청 ID·목적·금액·paymentId·items가 계약과 다름)만 {@link
 * InvalidRefundRequestException}이다. 결제 없음 같은 업무 판단은 하지 않는다.
 */
public final class RefundRequestParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENVELOPE =
            Set.of(
                    "eventId",
                    "eventType",
                    "schemaVersion",
                    "source",
                    "aggregateId",
                    "occurredAt",
                    "payload");
    private static final Pattern ORDER_REF = Pattern.compile("[A-Za-z0-9_-]{6,50}");
    private static final Pattern REFUND_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> PURPOSES = Set.of("USER_REFUND", "SYSTEM_COMPENSATION");

    private RefundRequestParser() {}

    public static JsonNode parse(String key, String value) {
        JsonNode event;
        try {
            event = JSON.readTree(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidRefundRequestException("JSON이 아닙니다.", e);
        }
        require(
                event != null && event.isObject() && event.size() == ENVELOPE.size(),
                "봉투 필드가 계약과 다릅니다.");
        event.fieldNames().forEachRemaining(f -> require(ENVELOPE.contains(f), "모르는 봉투 필드: " + f));
        require("RefundRequested".equals(event.path("eventType").asText()), "eventType이 다릅니다.");
        require(
                event.path("schemaVersion").isInt() && event.path("schemaVersion").intValue() == 1,
                "schemaVersion이 다릅니다.");
        require("commercecore-service".equals(event.path("source").asText()), "source가 다릅니다.");
        require(isUuid(event.path("eventId")), "eventId가 UUID가 아닙니다.");

        JsonNode payload = event.path("payload");
        require(payload.isObject(), "payload가 객체가 아닙니다.");
        String orderRef = payload.path("orderRef").asText();
        require(
                payload.path("orderRef").isTextual() && ORDER_REF.matcher(orderRef).matches(),
                "orderRef 형식이 다릅니다.");
        require(
                orderRef.equals(key) && orderRef.equals(event.path("aggregateId").asText()),
                "키·aggregateId·orderRef가 다릅니다.");
        require(
                payload.path("refundRequestId").isTextual()
                        && REFUND_REQUEST_ID
                                .matcher(payload.path("refundRequestId").asText())
                                .matches(),
                "refundRequestId 형식이 다릅니다.");
        require(PURPOSES.contains(payload.path("purpose").asText()), "purpose가 다릅니다.");
        require(
                payload.path("amount").isIntegralNumber()
                        && payload.path("amount").canConvertToLong()
                        && payload.path("amount").longValue() >= 0,
                "amount가 0 이상 정수가 아닙니다.");
        if (payload.has("paymentId")) {
            require(
                    payload.get("paymentId").isIntegralNumber()
                            && payload.get("paymentId").canConvertToLong()
                            && payload.get("paymentId").longValue() >= 1,
                    "paymentId가 양의 정수가 아닙니다.");
        }
        require(
                payload.path("items").isArray() && !payload.path("items").isEmpty(),
                "items가 비었습니다.");
        return payload;
    }

    private static boolean isUuid(JsonNode node) {
        try {
            return node.isTextual()
                    && UUID.fromString(node.asText()).toString().equalsIgnoreCase(node.asText());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidRefundRequestException(message);
        }
    }
}
