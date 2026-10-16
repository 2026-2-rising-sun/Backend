package com.shoppinglive.commerce.order.event;

import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 주문 상태 저장과 같은 트랜잭션에서 알림에 필요한 사본을 기록한다. */
@Component
public class PaymentGroupEventPublisher {
    public static final String TOPIC = "commerce.payment-group.v1";
    private final JdbcTemplate jdbc;
    private final EventOutbox outbox;

    public PaymentGroupEventPublisher(JdbcTemplate jdbc, EventOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String type, long groupId, Map<String, Object> details) {
        var group = jdbc.queryForMap("SELECT * FROM payment_group WHERE id=?", groupId);
        var orders =
                jdbc.query(
                        """
            SELECT order_number,product_name_snapshot,payable_amount FROM orders
             WHERE payment_group_id=? ORDER BY id
            """,
                        (rs, row) ->
                                Map.<String, Object>of(
                                        "orderNumber",
                                        rs.getString(1),
                                        "productName",
                                        rs.getString(2),
                                        "amount",
                                        rs.getLong(3)),
                        groupId);
        String number = (String) group.get("group_number");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentGroupId", groupId);
        payload.put("groupNumber", number);
        payload.put("memberId", group.get("member_id"));
        payload.put("currency", "KRW");
        payload.put("orderCount", orders.size());
        payload.put("orders", orders);
        payload.put("expiresAt", instant(group.get("expires_at")));
        payload.putAll(details);
        outbox.append(
                TOPIC,
                EventEnvelope.create(type, 1, "commerce-service", number, Instant.now(), payload));
    }

    private static Instant instant(Object value) {
        if (value instanceof java.time.OffsetDateTime time) return time.toInstant();
        return ((java.sql.Timestamp) value).toInstant();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refunded(long groupId, long requestId, long amount) {
        Long cumulative =
                jdbc.queryForObject(
                        """
            SELECT COALESCE(SUM(refund_amount),0) FROM refund_request
             WHERE payment_group_id=? AND status='SUCCESS'
            """,
                        Long.class,
                        groupId);
        publish(
                "RefundCompleted",
                groupId,
                Map.of(
                        "refundRequestId",
                        requestId,
                        "refundedAmount",
                        amount,
                        "cumulativeRefundAmount",
                        cumulative));
    }
}
