package com.shoppinglive.shopping.coupon.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 쿠폰 표시정보를 Shopping 자체 모델({@link CouponSnapshot})로 해석하고 계약 범위를 검증한다.
 *
 * <p>판매정보 parser 와 같은 이유로 공유 DTO 를 쓰지 않는다. {@code eventType} 은 읽지 않는다. 세 이벤트 모두 쿠폰 전체 상태를 싣기 때문에 같은
 * 방식으로 저장한다.
 *
 * <p>{@link #snapshot(JsonNode)} 는 동기화 API 응답 항목에도 쓴다. 이벤트 payload 와 API 의 {@code
 * CouponDisplaySnapshot} 이 같은 모양이라, 두 입력이 같은 검증을 통과해야 replica 에 들어간다.
 */
public final class CouponInfoEventMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ID_LENGTH = 36;
    private static final int MAX_NAME_LENGTH = 100;

    private CouponInfoEventMessage() {}

    /** 이벤트 원문(봉투 + payload)을 해석한다. 봉투의 발행 서비스와 aggregateId 도 확인한다. */
    static CouponSnapshot parse(String raw) {
        JsonNode root = readRoot(raw);
        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null
                || !schemaVersion.isIntegralNumber()
                || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != CouponInfoEventContract.SCHEMA_VERSION) {
            throw invalid("지원하지 않는 schemaVersion입니다.");
        }
        CouponSnapshot snapshot = snapshot(root.get("payload"));
        validateEnvelope(root, snapshot.couponId());
        return snapshot;
    }

    /**
     * {@code CouponDisplaySnapshot} 객체 하나를 해석한다.
     *
     * @throws InvalidMessageException 필드가 없거나 계약 범위를 벗어남
     */
    public static CouponSnapshot snapshot(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("snapshot은 JSON 객체여야 합니다.");
        }
        String couponId = textOf(node, "couponId");
        if (couponId.length() > MAX_ID_LENGTH) {
            throw invalid("couponId는 " + MAX_ID_LENGTH + "자 이하여야 합니다.");
        }
        String name = textOf(node, "name");
        if (name.length() > MAX_NAME_LENGTH) {
            throw invalid("name은 " + MAX_NAME_LENGTH + "자 이하여야 합니다.");
        }
        Instant startsAt = instantOf(node, "startsAt");
        Instant endsAt = instantOf(node, "endsAt");
        JsonNode soldOut = node.get("soldOut");
        if (soldOut == null || !soldOut.isBoolean()) {
            throw invalid("soldOut은 불리언이어야 합니다.");
        }
        return new CouponSnapshot(
                couponId,
                longAtLeast(node, "snapshotVersion", 1),
                name,
                longAtLeast(node, "fixedDiscount", 1),
                productIds(node.get("productIds")),
                startsAt,
                endsAt,
                instantOf(node, "expiresAt"),
                soldOut.booleanValue());
    }

    private static void validateEnvelope(JsonNode root, String couponId) {
        try {
            var envelope =
                    new EventEnvelope<>(
                            textOf(root, "eventId"),
                            textOf(root, "eventType"),
                            CouponInfoEventContract.SCHEMA_VERSION,
                            textOf(root, "source"),
                            textOf(root, "aggregateId"),
                            Instant.parse(textOf(root, "occurredAt")),
                            root.get("payload"));
            if (!"commerce-service".equals(envelope.source())
                    || !couponId.equals(envelope.aggregateId())) {
                throw invalid("발행 서비스 또는 쿠폰 식별자가 계약과 일치하지 않습니다.");
            }
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid("이벤트 봉투가 계약과 일치하지 않습니다: " + exception.getMessage());
        }
    }

    // 중복 상품을 거부한다. 계약이 uniqueItems 이고, 그대로 넣으면 연결 표의 PK 충돌로 재시도가 끝나지 않는다.
    private static List<Long> productIds(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw invalid("productIds는 배열이어야 합니다.");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() < 1) {
                throw invalid("productIds에는 1 이상의 정수만 포함할 수 있습니다.");
            }
            ids.add(item.longValue());
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw invalid("productIds는 중복될 수 없습니다.");
        }
        return ids;
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

    private static long longAtLeast(JsonNode node, String field, long min) {
        JsonNode value = node.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.longValue() < min) {
            throw invalid(field + "는 " + min + " 이상의 정수여야 합니다.");
        }
        return value.longValue();
    }

    private static InvalidMessageException invalid(String reason) {
        return new InvalidMessageException(reason);
    }

    /** 해석·검증 실패. 다시 읽어도 결과가 같아 error handler 가 재시도 없이 DLT 로 보낸다. */
    public static final class InvalidMessageException extends RuntimeException {

        InvalidMessageException(String reason) {
            super(reason);
        }
    }
}
