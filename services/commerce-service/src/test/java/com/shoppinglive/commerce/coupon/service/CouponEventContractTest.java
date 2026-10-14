package com.shoppinglive.commerce.coupon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.commerce.coupon.dto.CouponDisplaySnapshot;
import com.shoppinglive.commerce.coupon.event.CouponDisplayEventPublisher;
import com.shoppinglive.common.events.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CouponEventContractTest {
    @Test
    void 실제_봉투와_스냅샷_직렬화_표본을_YAML_검사에_제공한다() throws Exception {
        var mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        var start = Instant.parse("2026-10-15T09:00:00Z");
        for (var type : CouponDisplayEventPublisher.Type.values()) {
            var snapshot =
                    new CouponDisplaySnapshot(
                            "coupon-opaque-id",
                            type.ordinal() + 1L,
                            "상품 할인 쿠폰",
                            1000,
                            List.of(501L, 502L),
                            start,
                            start.plusSeconds(3600),
                            start.plusSeconds(7200),
                            type == CouponDisplayEventPublisher.Type.CouponSoldOut);
            var event =
                    EventEnvelope.create(
                            type.name(),
                            1,
                            "commerce-service",
                            snapshot.couponId(),
                            start.minusSeconds(3600),
                            snapshot);
            mapper.writeValue(
                    directory.resolve(type.name() + ".json").toFile(),
                    Map.of(
                            "contract",
                            "commerce-coupon-info.yaml",
                            "message",
                            type.name(),
                            "payload",
                            event));
        }
    }
}
