package com.shoppinglive.commercecore.payment.service;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 보상 행은 완료 뒤에도 남아 승인 재개를 막는다. 호출자는 구매 guard와 그룹 잠금을 확보한다. */
@Repository
public class RefundIntentStore {
    private final JdbcTemplate jdbc;

    public RefundIntentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean fenced(long groupId) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM core_refund_intent WHERE payment_group_id=? AND purpose='SYSTEM_COMPENSATION'",
                        Long.class,
                        groupId)
                > 0;
    }

    public Compensation compensation(long groupId) {
        var rows =
                jdbc.query(
                        "SELECT refund_request_id,status,outcome,completed_at FROM core_refund_intent WHERE payment_group_id=? AND purpose='SYSTEM_COMPENSATION'",
                        (r, n) ->
                                new Compensation(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getTimestamp(4) == null
                                                ? null
                                                : r.getTimestamp(4).toInstant()),
                        groupId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public record Compensation(
            String requestId, String status, String outcome, Instant completedAt) {}
}
