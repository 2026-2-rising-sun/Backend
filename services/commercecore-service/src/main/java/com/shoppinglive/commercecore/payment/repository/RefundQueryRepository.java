package com.shoppinglive.commercecore.payment.repository;

import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefundQueryRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public RefundQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public String kafkaRequestId(long id) {
        var ids =
                jdbc.queryForList(
                        "SELECT refund_request_id FROM core_refund_intent WHERE user_request_id=?",
                        String.class,
                        id);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public List<RefundableTarget> sheetTargets(
            com.shoppinglive.commercecore.order.entity.PaymentGroup group) {
        return jdbc.query(
                """
            SELECT o.*,COALESCE(x.succeeded_quantity,0) AS succeeded_quantity,COALESCE(x.held_quantity,0) AS held_quantity,COALESCE(x.success_amount,0) AS success_amount,COALESCE(x.held_amount,0) AS held_amount,
              p.approved_at,EXISTS(SELECT 1 FROM core_refund_intent i WHERE i.payment_group_id=o.payment_group_id AND i.purpose='SYSTEM_COMPENSATION') AS fenced
            FROM orders o LEFT JOIN order_payment_relay p ON p.payment_group_id=o.payment_group_id
            LEFT JOIN (SELECT t.order_id,SUM(CASE WHEN r.status='SUCCESS' THEN t.quantity ELSE 0 END) AS succeeded_quantity,
                SUM(CASE WHEN r.status IN ('PROCESSING','UNKNOWN') THEN t.quantity ELSE 0 END) AS held_quantity,
                SUM(CASE WHEN r.status='SUCCESS' THEN t.refund_amount ELSE 0 END) AS success_amount,
                SUM(CASE WHEN r.status IN ('PROCESSING','UNKNOWN') THEN t.refund_amount ELSE 0 END) AS held_amount
                FROM refund_target_order t JOIN refund_request r ON r.id=t.refund_request_id GROUP BY t.order_id) x ON x.order_id=o.id
            WHERE o.payment_group_id=? ORDER BY o.id
            """,
                (r, n) -> {
                    int quantity = r.getInt("quantity"),
                            done = r.getInt("succeeded_quantity"),
                            held = r.getInt("held_quantity");
                    long remaining = r.getLong("payable_amount") - r.getLong("success_amount");
                    String reason = null;
                    if (r.getBoolean("fenced")) reason = "SYSTEM_COMPENSATION";
                    else if (!java.util.Set.of("PAID", "PARTIALLY_REFUNDED")
                            .contains(group.getStatus().name())) reason = "PAYMENT_NOT_COMPLETED";
                    else if (r.getTimestamp("approved_at") == null
                            || !com.shoppinglive.commercecore.payment.service.RefundWindowPolicy
                                    .isOpen(
                                            java.time.Instant.now(),
                                            r.getTimestamp("approved_at").toInstant()))
                        reason = "REFUND_WINDOW_EXPIRED";
                    else if (quantity - done - held <= 0) reason = "ALREADY_REQUESTED";
                    return new RefundableTarget(
                            r.getLong("id"),
                            (Long) r.getObject("source_cart_item_id"),
                            r.getString("product_name_snapshot"),
                            quantity,
                            remaining,
                            reason == null,
                            reason,
                            r.getLong("unit_price"),
                            done,
                            held,
                            quantity - done - held,
                            remaining - r.getLong("held_amount"));
                },
                group.getId());
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

    public List<RequestRow> requests(Long groupId) {
        return jdbc.query(
                """
            SELECT id,payment_group_id,refund_amount,status,requested_at,resolved_at,retry_count,next_action_at
            FROM refund_request WHERE payment_group_id=? ORDER BY requested_at DESC,id DESC
            """,
                (rs, row) -> requestRow(rs),
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

    public List<RequestRow> sellerRequests(String sellerId, int size, long offset) {
        return jdbc.query(
                """
            SELECT r.id,r.payment_group_id,r.refund_amount,r.status,r.requested_at,r.resolved_at,r.retry_count,r.next_action_at
            FROM refund_request r
            WHERE EXISTS (SELECT 1 FROM refund_target_order t
                WHERE t.refund_request_id=r.id AND t.seller_id_snapshot=?)
            ORDER BY r.id DESC LIMIT ? OFFSET ?
            """,
                (rs, row) -> requestRow(rs),
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
                """
            SELECT id,payment_group_id,refund_amount,status,requested_at,resolved_at,retry_count,next_action_at
            FROM refund_request WHERE id=?
            """,
                rs -> rs.next() ? requestRow(rs) : null,
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
            SELECT o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,COALESCE(t.quantity,o.quantity) AS quantity,t.refund_amount
              FROM refund_target_order t JOIN orders o ON o.id=t.order_id
             WHERE t.refund_request_id=? ORDER BY t.order_id
            """,
                (rs, row) -> target(rs),
                id);
    }

    public List<RefundTarget> sellerTargets(long id, String sellerId) {
        return jdbc.query(
                """
            SELECT o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,COALESCE(t.quantity,o.quantity) AS quantity,t.refund_amount
              FROM refund_target_order t JOIN orders o ON o.id=t.order_id
             WHERE t.refund_request_id=? AND t.seller_id_snapshot=? ORDER BY t.order_id
            """,
                (rs, row) -> target(rs),
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
            long id,
            long groupId,
            long amount,
            RefundStatus status,
            Instant requestedAt,
            Instant resolvedAt,
            int retryCount,
            Instant nextActionAt) {}

    public record ExistingRequest(long id, long groupId, String fingerprint, RefundStatus status) {}

    public Map<Long, List<RefundTarget>> targetsForGroup(long groupId) {
        return bulkTargets(
                """
            SELECT t.refund_request_id,o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,COALESCE(t.quantity,o.quantity) AS quantity,t.refund_amount
            FROM refund_target_order t JOIN orders o ON o.id=t.order_id
            JOIN refund_request r ON r.id=t.refund_request_id
            WHERE r.payment_group_id=:groupId ORDER BY t.refund_request_id,t.order_id
            """,
                Map.of("groupId", groupId));
    }

    public Map<Long, List<RefundTarget>> sellerTargetsByRequestIds(
            List<Long> ids, String sellerId) {
        return bulkTargets(
                """
            SELECT t.refund_request_id,o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,COALESCE(t.quantity,o.quantity) AS quantity,t.refund_amount
            FROM refund_target_order t JOIN orders o ON o.id=t.order_id
            WHERE t.refund_request_id IN (:ids) AND t.seller_id_snapshot=:sellerId
            ORDER BY t.refund_request_id,t.order_id
            """,
                Map.of("ids", ids, "sellerId", sellerId));
    }

    private Map<Long, List<RefundTarget>> bulkTargets(String sql, Map<String, ?> parameters) {
        return named.query(
                sql,
                parameters,
                rs -> {
                    Map<Long, List<RefundTarget>> targets = new HashMap<>();
                    while (rs.next()) {
                        targets.computeIfAbsent(
                                        rs.getLong("refund_request_id"), id -> new ArrayList<>())
                                .add(target(rs));
                    }
                    return targets;
                });
    }

    public Map<Long, Long> cumulativeAmounts(List<Long> groupIds) {
        return cumulativeAmounts(
                """
            SELECT payment_group_id,COALESCE(SUM(refund_amount),0) AS cumulative_amount
            FROM refund_request WHERE payment_group_id IN (:groupIds) AND status='SUCCESS'
            GROUP BY payment_group_id
            """,
                Map.of("groupIds", groupIds));
    }

    public Map<Long, Long> sellerCumulativeAmounts(List<Long> groupIds, String sellerId) {
        // 페이지에 포함된 요청이 아니라 각 결제 묶음의 전체 성공 환불을 누적한다.
        return cumulativeAmounts(
                """
            SELECT r.payment_group_id,COALESCE(SUM(t.refund_amount),0) AS cumulative_amount
            FROM refund_request r JOIN refund_target_order t ON t.refund_request_id=r.id
            WHERE r.payment_group_id IN (:groupIds) AND r.status='SUCCESS' AND t.seller_id_snapshot=:sellerId
            GROUP BY r.payment_group_id
            """,
                Map.of("groupIds", groupIds, "sellerId", sellerId));
    }

    private Map<Long, Long> cumulativeAmounts(String sql, Map<String, ?> parameters) {
        return named.query(
                sql,
                parameters,
                rs -> {
                    Map<Long, Long> amounts = new HashMap<>();
                    while (rs.next())
                        amounts.put(
                                rs.getLong("payment_group_id"), rs.getLong("cumulative_amount"));
                    return amounts;
                });
    }

    private static RequestRow requestRow(ResultSet rs) throws SQLException {
        return new RequestRow(
                rs.getLong("id"),
                rs.getLong("payment_group_id"),
                rs.getLong("refund_amount"),
                RefundStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("requested_at").toInstant(),
                rs.getTimestamp("resolved_at") == null
                        ? null
                        : rs.getTimestamp("resolved_at").toInstant(),
                rs.getInt("retry_count"),
                rs.getTimestamp("next_action_at").toInstant());
    }

    private static RefundTarget target(ResultSet rs) throws SQLException {
        return new RefundTarget(
                (Long) rs.getObject("source_cart_item_id"),
                rs.getLong("order_id"),
                rs.getLong("product_id_snapshot"),
                rs.getString("product_name_snapshot"),
                rs.getInt("quantity"),
                rs.getLong("refund_amount"));
    }
}
