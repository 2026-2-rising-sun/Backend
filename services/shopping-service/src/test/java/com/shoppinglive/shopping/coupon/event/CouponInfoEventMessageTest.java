package com.shoppinglive.shopping.coupon.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CouponInfoEventMessageTest {

    private static final String PAYLOAD =
            """
            {"couponId":"coupon-opaque-id","snapshotVersion":2,"name":"상품 할인 쿠폰",
             "fixedDiscount":1000,"productIds":[501,502],
             "startsAt":"2026-10-15T09:00:00Z","endsAt":"2026-10-16T09:00:00Z",
             "expiresAt":"2026-10-17T09:00:00Z","soldOut":true}""";

    @Test
    @DisplayName("계약 예시 이벤트를 쿠폰 전체 상태로 읽는다")
    void parsesContractExample() {
        CouponSnapshot snapshot = CouponInfoEventMessage.parse(event(PAYLOAD));

        assertThat(snapshot)
                .isEqualTo(
                        new CouponSnapshot(
                                "coupon-opaque-id",
                                2,
                                "상품 할인 쿠폰",
                                1000,
                                List.of(501L, 502L),
                                Instant.parse("2026-10-15T09:00:00Z"),
                                Instant.parse("2026-10-16T09:00:00Z"),
                                Instant.parse("2026-10-17T09:00:00Z"),
                                true));
    }

    @ParameterizedTest
    @DisplayName("계약을 벗어난 메시지는 거부한다")
    @ValueSource(
            strings = {
                "\"productIds\":[501,502]>\"productIds\":[501,501]",
                "\"fixedDiscount\":1000>\"fixedDiscount\":0",
                "\"snapshotVersion\":2>\"snapshotVersion\":0",
                "\"soldOut\":true>\"soldOut\":\"true\"",
                "\"startsAt\":\"2026-10-15T09:00:00Z\">\"startsAt\":\"2026-10-15\"",
                "\"name\":\"상품 할인 쿠폰\">\"name\":\" \""
            })
    void rejectsPayloadOutsideContract(String replacement) {
        String[] parts = replacement.split(">");
        String raw = event(PAYLOAD.replace(parts[0], parts[1]));

        assertThatThrownBy(() -> CouponInfoEventMessage.parse(raw))
                .isInstanceOf(CouponInfoEventMessage.InvalidMessageException.class);
    }

    @ParameterizedTest
    @DisplayName("봉투가 계약과 다르면 거부한다")
    @ValueSource(
            strings = {
                "\"source\":\"commerce-service\">\"source\":\"live-service\"",
                "\"aggregateId\":\"coupon-opaque-id\">\"aggregateId\":\"other\"",
                "\"schemaVersion\":1>\"schemaVersion\":2",
                "\"eventId\":\"74b623b8-90ee-4f65-919c-d9ad2ea46fc1\">\"eventId\":\"not-uuid\""
            })
    void rejectsEnvelopeOutsideContract(String replacement) {
        String[] parts = replacement.split(">");
        String raw = event(PAYLOAD).replace(parts[0], parts[1]);

        assertThatThrownBy(() -> CouponInfoEventMessage.parse(raw))
                .isInstanceOf(CouponInfoEventMessage.InvalidMessageException.class);
    }

    @Test
    @DisplayName("이름이 100자를 넘으면 거부한다")
    void rejectsTooLongName() {
        String raw = event(PAYLOAD.replace("상품 할인 쿠폰", "가".repeat(101)));

        assertThatThrownBy(() -> CouponInfoEventMessage.parse(raw))
                .isInstanceOf(CouponInfoEventMessage.InvalidMessageException.class);
    }

    private static String event(String payload) {
        return """
               {"eventId":"74b623b8-90ee-4f65-919c-d9ad2ea46fc1","eventType":"CouponUpdated",
                "schemaVersion":1,"source":"commerce-service","aggregateId":"coupon-opaque-id",
                "occurredAt":"2026-10-14T09:00:00Z","payload":%s}"""
                .formatted(payload);
    }
}
