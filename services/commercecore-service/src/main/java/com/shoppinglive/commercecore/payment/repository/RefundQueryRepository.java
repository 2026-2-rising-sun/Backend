package com.shoppinglive.commercecore.payment.repository;

import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefundQueryRepository {
    private final JdbcTemplate jdbc;

    public RefundQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> requestedOrderIds(Long groupId) {
        return jdbc.query(
                """
                SELECT t.order_id FROM refund_request r
                JOIN refund_target_order t ON t.refund_request_id=r.id
                WHERE r.payment_group_id=? AND r.status <> 'FAILED'
                """,
                (rs, row) -> rs.getLong(1),
                groupId);
    }

    public List<Long> requestIds(Long groupId) {
        return jdbc.query(
                "SELECT id FROM refund_request WHERE payment_group_id=? ORDER BY requested_at DESC,id DESC",
                (rs, row) -> rs.getLong(1),
                groupId);
    }

    public Long ownedRequestId(long requestId, Long groupId, String memberId) {
        return jdbc.query(
                "SELECT id FROM refund_request WHERE id=? AND payment_group_id=? AND member_id=?",
                rs -> rs.next() ? rs.getLong(1) : null,
                requestId,
                groupId,
                memberId);
    }

    public List<Long> sellerRequestIds(String sellerId, int size, long offset) {
        return jdbc.query(
                """
            SELECT DISTINCT r.id FROM refund_request r
            JOIN refund_target_order t ON t.refund_request_id=r.id
            WHERE t.seller_id_snapshot=? ORDER BY r.id DESC LIMIT ? OFFSET ?
            """,
                (rs, row) -> rs.getLong(1),
                sellerId,
                size,
                offset);
    }

    public Integer sellerTargetCount(long requestId, String sellerId) {
        return jdbc.queryForObject(
                """
            SELECT COUNT(*) FROM refund_target_order
            WHERE refund_request_id=? AND seller_id_snapshot=?
            """,
                Integer.class,
                requestId,
                sellerId);
    }

    public RequestRow request(long id) {
        return jdbc.query(
                "SELECT payment_group_id,refund_amount,status,requested_at,resolved_at,retry_count,next_action_at FROM refund_request WHERE id=?",
                rs ->
                        rs.next()
                                ? new RequestRow(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        RefundStatus.valueOf(rs.getString(3)),
                                        rs.getTimestamp(4).toInstant(),
                                        rs.getTimestamp(5) == null
                                                ? null
                                                : rs.getTimestamp(5).toInstant(),
                                        rs.getInt(6),
                                        rs.getTimestamp(7).toInstant())
                                : null,
                id);
    }

    public long cumulativeAmount(long groupId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(refund_amount),0) FROM refund_request WHERE payment_group_id=? AND status='SUCCESS'",
                Long.class,
                groupId);
    }

    public List<RefundTarget> targets(long id) {
        return jdbc.query(
                """
            SELECT o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,o.quantity,t.refund_amount
              FROM refund_target_order t JOIN orders o ON o.id=t.order_id
             WHERE t.refund_request_id=? ORDER BY t.order_id
            """,
                (rs, row) ->
                        new RefundTarget(
                                (Long) rs.getObject(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getInt(5),
                                rs.getLong(6)),
                id);
    }

    public List<RefundTarget> sellerTargets(long id, String sellerId) {
        return jdbc.query(
                """
            SELECT o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,o.quantity,t.refund_amount
              FROM refund_target_order t JOIN orders o ON o.id=t.order_id
             WHERE t.refund_request_id=? AND t.seller_id_snapshot=? ORDER BY t.order_id
            """,
                (rs, row) ->
                        new RefundTarget(
                                (Long) rs.getObject(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getString(4),
                                rs.getInt(5),
                                rs.getLong(6)),
                id,
                sellerId);
    }

    public Long sellerCumulativeAmount(long groupId, String sellerId) {
        return jdbc.queryForObject(
                """
            SELECT COALESCE(SUM(t.refund_amount),0) FROM refund_request r
            JOIN refund_target_order t ON t.refund_request_id=r.id
            WHERE r.payment_group_id=? AND r.status='SUCCESS' AND t.seller_id_snapshot=?
            """,
                Long.class,
                groupId,
                sellerId);
    }

    public ExistingRequest findByKey(String member, String key) {
        List<ExistingRequest> rows =
                jdbc.query(
                        "SELECT id,payment_group_id,request_fingerprint,status FROM refund_request WHERE member_id=? AND idempotency_key=?",
                        (rs, row) ->
                                new ExistingRequest(
                                        rs.getLong(1),
                                        rs.getLong(2),
                                        rs.getString(3),
                                        RefundStatus.valueOf(rs.getString(4))),
                        member,
                        key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public record RequestRow(
            long groupId,
            long amount,
            RefundStatus status,
            Instant requestedAt,
            Instant resolvedAt,
            int retryCount,
            Instant nextActionAt) {}

    public record ExistingRequest(long id, long groupId, String fingerprint, RefundStatus status) {}
}
