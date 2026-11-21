package com.shoppinglive.payment.refund.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code commerce.refund.requests.v1} 메시지를 읽어 요청 payload를 고치지 않고 돌려준다. 계약 원본은 {@code
 * contracts/events/commerce-refund-requests.yaml}.
 *
 * <p>요청 payload는 결과에 그대로 실린다. 그래서 요청 계약을 어긴 메시지(봉투·키·필드 집합·값 형식)는 결과를 만들지 않고 {@link
 * InvalidRefundRequestException}으로 DLT에 보낸다. 어긴 원문을 결과에 실으면 Core가 거부하고 무한 재시도해 파티션이 막힌다(#389). 결제
 * 없음 같은 업무 판단은 하지 않는다.
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
    // 결과에 그대로 실리므로 Core 결과 소비자가 받는 요청 필드만 허용한다. paymentId만 선택이다
    private static final Set<String> REQUIRED =
            Set.of(
                    "refundRequestId",
                    "orderRef",
                    "purpose",
                    "reason",
                    "currency",
                    "amount",
                    "items",
                    "requestedAt");
    private static final Set<String> SELLER_ITEM =
            Set.of("sellerOrderNumber", "amount", "orderItems");
    private static final Set<String> ORDER_ITEM = Set.of("orderItemId", "quantity", "amount");
    private static final Pattern ORDER_REF = Pattern.compile("[A-Za-z0-9_-]{6,50}");
    private static final Pattern REFUND_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> PURPOSES = Set.of("USER_REFUND", "SYSTEM_COMPENSATION");
    private static final Set<String> REASONS =
            Set.of(
                    "CUSTOMER_REQUEST",
                    "APPROVAL_RESPONSE_LOST",
                    "APPROVAL_UNKNOWN",
                    "LATE_APPROVAL",
                    "ORDER_EXPIRED",
                    "ORDER_CANCELLED");

    private RefundRequestParser() {}

    public static JsonNode parse(String key, String value) {
        JsonNode event;
        try {
            event = JSON.readTree(value);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidRefundRequestException("JSON이 아닙니다.", e);
        }
        require(event != null && hasExactly(event, ENVELOPE), "봉투 필드가 계약과 다릅니다.");
        require("RefundRequested".equals(event.path("eventType").asText()), "eventType이 다릅니다.");
        require(
                event.path("schemaVersion").isInt() && event.path("schemaVersion").intValue() == 1,
                "schemaVersion이 다릅니다.");
        require("commercecore-service".equals(event.path("source").asText()), "source가 다릅니다.");
        require(isUuid(event.path("eventId")), "eventId가 UUID가 아닙니다.");

        JsonNode payload = event.path("payload");
        require(payload.isObject(), "payload가 객체가 아닙니다.");
        payload.fieldNames()
                .forEachRemaining(
                        f ->
                                require(
                                        REQUIRED.contains(f) || f.equals("paymentId"),
                                        "모르는 payload 필드: " + f));
        REQUIRED.forEach(f -> require(payload.has(f), "필수 payload 필드가 없습니다: " + f));

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
        String purpose = payload.path("purpose").asText();
        String reason = payload.path("reason").asText();
        require(PURPOSES.contains(purpose), "purpose가 다릅니다.");
        require(REASONS.contains(reason), "reason이 다릅니다.");
        require(
                !purpose.equals("USER_REFUND") || reason.equals("CUSTOMER_REQUEST"),
                "고객 환불의 reason은 CUSTOMER_REQUEST입니다.");
        require(
                !purpose.equals("SYSTEM_COMPENSATION") || !reason.equals("CUSTOMER_REQUEST"),
                "시스템 보상의 reason은 CUSTOMER_REQUEST일 수 없습니다.");
        require("KRW".equals(payload.path("currency").asText()), "currency가 KRW가 아닙니다.");
        require(isTimestamp(payload.path("requestedAt")), "requestedAt이 시각이 아닙니다.");
        require(isMoney(payload.path("amount")), "amount가 0 이상 정수가 아닙니다.");
        if (payload.has("paymentId")) {
            require(isPositiveId(payload.get("paymentId")), "paymentId가 양의 정수가 아닙니다.");
        }
        requireItems(payload.path("items"), payload.path("amount").longValue());
        return payload;
    }

    /** Core가 결과의 items를 정규화할 때 요구하는 모양과 합계다. 0원 항목은 정상이다. */
    private static void requireItems(JsonNode items, long amount) {
        require(items.isArray() && !items.isEmpty() && items.size() <= 100, "items 개수가 다릅니다.");
        Set<String> sellerNumbers = new HashSet<>();
        Set<Long> orderItemIds = new HashSet<>();
        long total = 0;
        for (JsonNode seller : items) {
            require(hasExactly(seller, SELLER_ITEM), "판매자 항목 필드가 다릅니다.");
            String number = seller.path("sellerOrderNumber").asText();
            require(
                    seller.path("sellerOrderNumber").isTextual()
                            && !number.isEmpty()
                            && number.length() <= 64,
                    "sellerOrderNumber 형식이 다릅니다.");
            require(sellerNumbers.add(number), "sellerOrderNumber가 중복됩니다.");
            require(isMoney(seller.path("amount")), "판매자 항목 금액이 다릅니다.");
            JsonNode orderItems = seller.path("orderItems");
            require(
                    orderItems.isArray() && !orderItems.isEmpty() && orderItems.size() <= 100,
                    "orderItems 개수가 다릅니다.");
            long sellerTotal = 0;
            for (JsonNode item : orderItems) {
                require(hasExactly(item, ORDER_ITEM), "주문 항목 필드가 다릅니다.");
                require(isPositiveId(item.path("orderItemId")), "orderItemId가 양의 정수가 아닙니다.");
                require(
                        item.path("quantity").isInt() && item.path("quantity").intValue() >= 1,
                        "quantity가 1 이상 정수가 아닙니다.");
                require(isMoney(item.path("amount")), "주문 항목 금액이 다릅니다.");
                require(
                        orderItemIds.add(item.path("orderItemId").longValue()),
                        "orderItemId가 중복됩니다.");
                sellerTotal = add(sellerTotal, item.path("amount").longValue());
            }
            require(sellerTotal == seller.path("amount").longValue(), "판매자 금액과 항목 합계가 다릅니다.");
            total = add(total, sellerTotal);
        }
        require(total == amount, "요청 금액과 판매자 합계가 다릅니다.");
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new InvalidRefundRequestException("금액 합계가 범위를 넘습니다.", e);
        }
    }

    private static boolean isTimestamp(JsonNode node) {
        try {
            return node.isTextual() && Instant.parse(node.asText()) != null;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isMoney(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0;
    }

    private static boolean isPositiveId(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 1;
    }

    private static boolean hasExactly(JsonNode node, Set<String> fields) {
        if (!node.isObject() || node.size() != fields.size()) {
            return false;
        }
        for (String field : fields) {
            if (!node.has(field)) {
                return false;
            }
        }
        return true;
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
