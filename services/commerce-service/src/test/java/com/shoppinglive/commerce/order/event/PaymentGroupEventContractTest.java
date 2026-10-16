package com.shoppinglive.commerce.order.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.commerce.coupon.dto.CouponDisplaySnapshot;
import com.shoppinglive.commerce.coupon.event.CouponDisplayEventPublisher;
import com.shoppinglive.commerce.coupon.service.CouponClaimService.MemberCoupon;
import com.shoppinglive.commerce.coupon.service.CouponSnapshotService;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PaymentGroupEventContractTest {
    @Test
    @DisplayName("발행자가 생성한 7종의 실제 JSON을 계약 검증 표본으로 저장한다")
    void exportsPublisherEnvelopesForEveryNotificationEvent() throws Exception {
        var mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var outbox = mock(EventOutbox.class);
        var jdbc = mock(JdbcTemplate.class);
        String number = "PG-00000000-0000-4000-8000-000000000001";
        String member = "11111111-1111-4111-8111-111111111111";
        Instant now = Instant.parse("2026-10-17T00:00:00Z");
        when(jdbc.queryForMap(anyString(), eq(101L)))
                .thenReturn(
                        Map.of(
                                "group_number",
                                number,
                                "member_id",
                                member,
                                "expires_at",
                                Timestamp.from(now.plusSeconds(900))));
        List<Map<String, Object>> orders =
                List.of(
                        Map.of("orderNumber", "ORDER-A", "productName", "상품 A", "amount", 19000L),
                        Map.of("orderNumber", "ORDER-B", "productName", "상품 B", "amount", 5000L));
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
                        eq(101L)))
                .thenReturn(orders);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(101L))).thenReturn(19000L);
        var directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        doAnswer(
                        call -> {
                            EventEnvelope<?> event = call.getArgument(1);
                            String contract =
                                    event.eventType().equals("CouponIssued")
                                            ? "commerce-coupon-info.yaml"
                                            : "commerce-payment-group.yaml";
                            mapper.writeValue(
                                    directory.resolve(event.eventType() + ".json").toFile(),
                                    Map.of(
                                            "contract",
                                            contract,
                                            "message",
                                            event.eventType(),
                                            "payload",
                                            event));
                            assertThat(event.aggregateId()).isNotBlank();
                            return null;
                        })
                .when(outbox)
                .append(anyString(), any());
        var publisher = new PaymentGroupEventPublisher(jdbc, outbox);
        publisher.publish("PaymentGroupCreated", 101L, Map.of());
        publisher.publish(
                "PaymentSucceeded", 101L, Map.of("paymentId", 201L, "paidAmount", 24000L));
        publisher.publish(
                "PaymentFailed",
                101L,
                Map.of("paymentId", 201L, "attemptedAmount", 24000L, "failureCode", "REJECTED"));
        publisher.publish("OrderCancelled", 101L, Map.of("cancelledAmount", 24000L));
        publisher.publish("OrderExpired", 101L, Map.of("expiredAmount", 24000L));
        publisher.refunded(101L, 301L, 19000L);
        var snapshots = mock(CouponSnapshotService.class);
        when(snapshots.get("coupon"))
                .thenReturn(
                        new CouponDisplaySnapshot(
                                "coupon",
                                1,
                                "할인 쿠폰",
                                1000,
                                List.of(501L),
                                now.minusSeconds(60),
                                now.plusSeconds(1800),
                                now.plusSeconds(3600),
                                false));
        new CouponDisplayEventPublisher(snapshots, outbox)
                .issued(
                        member,
                        new MemberCoupon(
                                "22222222-2222-4222-8222-222222222222",
                                "coupon",
                                "할인 쿠폰",
                                1000,
                                "AVAILABLE",
                                now,
                                now.plusSeconds(3600)));
        verify(outbox, times(6)).append(eq(PaymentGroupEventPublisher.TOPIC), any());
        verify(outbox).append(eq("commerce.coupon-info.v1"), any());
    }
}
