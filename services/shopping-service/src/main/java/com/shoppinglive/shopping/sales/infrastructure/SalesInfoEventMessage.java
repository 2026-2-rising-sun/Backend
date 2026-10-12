package com.shoppinglive.shopping.sales.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.contracts.events.SalesInfoEventContract;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 판매정보 이벤트 한 건을 Shopping 자체 모델로 해석하고 검증한다.
 *
 * <p>공유 DTO({@code SalesInfoEvent})를 쓰지 않는 이유: 모르는 enum 값이나 schemaVersion 에서 생성자가 던져
 * 메시지 전체가 읽히지 않는다. 여기서는 필드를 직접 읽고, 모르는 {@code status} 만 PRIVATE 으로 바꾼다.
 * {@code eventType} 은 네 종류를 구분하지 않으므로 읽지 않는다.
 */
record SalesInfoEventMessage(long productId, long salesId, long snapshotVersion, long price,
                             SalesStatus status, int available) {

    private static final Logger log = LoggerFactory.getLogger(SalesInfoEventMessage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CURRENCY = "KRW";

    static SalesInfoEventMessage parse(String raw) {
        JsonNode root = readRoot(raw);
        if (!isSupportedSchemaVersion(root.get("schemaVersion"))) {
            throw invalid("unsupported schemaVersion");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalid("payload is missing");
        }
        long productId = longAtLeast(payload, "productId", 1);
        long salesId = longAtLeast(payload, "salesId", 1);
        long snapshotVersion = longAtLeast(payload, "snapshotVersion", 1);
        long price = longAtLeast(payload, "price", 1);
        int available = intAtLeast(payload, "available", 0);
        if (!isKrw(payload.get("currency"))) {
            throw invalid("currency must be KRW");
        }
        SalesStatus status = toSalesStatus(textOf(payload, "status"), productId, snapshotVersion);
        return new SalesInfoEventMessage(productId, salesId, snapshotVersion, price, status, available);
    }

    SalesInfo toSalesInfo() {
        return new SalesInfo(productId, salesId, price, status, available);
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

    // 모르는 값은 PRIVATE 으로 저장한다. 판매 중 상태가 옛 값으로 남으면 구매 화면이 계속 열리기 때문이다.
    private static SalesStatus toSalesStatus(String raw, long productId, long snapshotVersion) {
        for (SalesStatus status : SalesStatus.values()) {
            if (status.name().equals(raw)) {
                return status;
            }
        }
        log.warn("unknown sales status stored as PRIVATE: status={} productId={} snapshotVersion={}",
            raw, productId, snapshotVersion);
        return SalesStatus.PRIVATE;
    }

    private static boolean isSupportedSchemaVersion(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
            && node.intValue() == SalesInfoEventContract.SCHEMA_VERSION;
    }

    private static boolean isKrw(JsonNode currency) {
        return currency != null && currency.isTextual() && CURRENCY.equals(currency.textValue());
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
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min) {
            throw invalid(field + " must be an integer >= " + min);
        }
        return value.longValue();
    }

    private static int intAtLeast(JsonNode node, String field, int min) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min) {
            throw invalid(field + " must be an integer >= " + min);
        }
        return value.intValue();
    }

    private static InvalidMessageException invalid(String reason) {
        return new InvalidMessageException(reason);
    }

    /** 해석·검증 실패. listener 가 이것만 잡아 건너뛴다. */
    static final class InvalidMessageException extends RuntimeException {

        InvalidMessageException(String reason) {
            super(reason);
        }
    }
}
