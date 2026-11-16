package com.shoppinglive.payment.service;

import com.shoppinglive.payment.dto.RefundExecution;
import com.shoppinglive.payment.entity.RefundStatus;
import org.springframework.jdbc.core.JdbcTemplate;

final class RefundRows {
    static RefundExecution get(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject(
                "SELECT request_id,id,core_refund_request_id,payment_id,order_id,payment_group_id,reference_number,refund_amount,status,requested_at,resolved_at,retry_count,next_action_at FROM refund_execution WHERE id=?",
                (r, n) ->
                        new RefundExecution(
                                r.getString(1),
                                r.getLong(2),
                                r.getLong(3),
                                r.getLong(4),
                                r.getLong(5),
                                r.getLong(6),
                                r.getString(7),
                                r.getLong(8),
                                RefundStatus.valueOf(r.getString(9)),
                                r.getTimestamp(10).toInstant(),
                                r.getTimestamp(11) == null ? null : r.getTimestamp(11).toInstant(),
                                r.getInt(12),
                                r.getTimestamp(13).toInstant()),
                id);
    }
}
