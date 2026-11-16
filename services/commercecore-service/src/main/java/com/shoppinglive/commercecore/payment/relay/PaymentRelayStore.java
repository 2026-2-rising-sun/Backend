package com.shoppinglive.commercecore.payment.relay;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.common.core.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class PaymentRelayStore {
    private final JdbcTemplate jdbc;

    public PaymentRelayStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void snapshot(PaymentGroup group, List<Order> items, BigDecimal rate) {
        var sellers = new TreeMap<String, List<Order>>();
        for (Order item : items) {
            UUID.fromString(item.getSellerIdSnapshot());
            sellers.computeIfAbsent(item.getSellerIdSnapshot(), key -> new ArrayList<>()).add(item);
        }
        int sequence = 0;
        long sum = 0;
        for (var entry : sellers.entrySet()) {
            long total = 0, discount = 0;
            for (Order item : entry.getValue()) {
                total = Math.addExact(total, item.getTotalAmount());
                discount = Math.addExact(discount, item.getDiscountAmount());
            }
            long payable = total - discount;
            if (payable <= 0)
                throw new BusinessException(
                        ErrorCode.INVALID_REQUEST, "새 Payment 계약은 판매자별 실결제액이 양수여야 합니다.");
            jdbc.update(
                    "INSERT INTO seller_order(payment_group_id,seller_order_number,seller_id,total_amount,discount_amount,payable_amount,commission_rate) VALUES(?,?,?,?,?,?,?)",
                    group.getId(),
                    group.getGroupNumber() + "-S" + (++sequence),
                    entry.getKey(),
                    total,
                    discount,
                    payable,
                    rate);
            sum = Math.addExact(sum, payable);
        }
        if (sum != group.getPayableAmount())
            throw new IllegalStateException("판매자별 결제 합계가 주문과 다릅니다.");
        jdbc.update(
                "INSERT INTO order_payment_relay(payment_group_id,payment_status) VALUES(?,'PREPARING')",
                group.getId());
    }

    public Create creation(PaymentGroup group) {
        var sellers =
                jdbc.query(
                        "SELECT seller_order_number,seller_id,payable_amount,commission_rate FROM seller_order WHERE payment_group_id=? ORDER BY seller_id",
                        (r, n) ->
                                new SellerAmount(
                                        r.getString(1),
                                        r.getString(2),
                                        r.getLong(3),
                                        r.getBigDecimal(4)),
                        group.getId());
        if (sellers.isEmpty()) throw new IllegalStateException("저장된 판매자 주문이 없습니다.");
        return new Create(
                group.getGroupNumber(),
                group.getMemberId(),
                group.getPayableAmount(),
                group.getExpiresAt(),
                sellers);
    }

    public Binding binding(long groupId) {
        var rows =
                jdbc.query(
                        "SELECT payment_group_id,remote_payment_id,pg_order_id,payment_status,approved_at FROM order_payment_relay WHERE payment_group_id=?",
                        (r, n) ->
                                new Binding(
                                        r.getLong(1),
                                        (Long) r.getObject(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getTimestamp(5) == null
                                                ? null
                                                : r.getTimestamp(5).toInstant()),
                        groupId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void prepared(long groupId, Created result) {
        var prior = Objects.requireNonNull(binding(groupId));
        if (prior.paymentId() != null && !prior.paymentId().equals(result.paymentId()))
            throw conflict("원결제 ID가 변경되었습니다.");
        jdbc.update(
                "UPDATE order_payment_relay SET remote_payment_id=?,pg_order_id=?,payment_status=? WHERE payment_group_id=? AND approved_at IS NULL",
                result.paymentId(),
                result.pgOrderId(),
                result.status(),
                groupId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkout(long groupId, String pgOrderId) {
        jdbc.update(
                "UPDATE order_payment_relay SET pg_order_id=? WHERE payment_group_id=? AND approved_at IS NULL",
                pgOrderId,
                groupId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void status(long groupId, String status) {
        jdbc.update(
                "UPDATE order_payment_relay SET payment_status=? WHERE payment_group_id=? AND approved_at IS NULL",
                status,
                groupId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void approved(long groupId, Instant approvedAt) {
        jdbc.update(
                "UPDATE order_payment_relay SET payment_status='APPROVED',approved_at=? WHERE payment_group_id=? AND approved_at IS NULL",
                Timestamp.from(approvedAt),
                groupId);
    }

    public long deliveryGroup(long id) {
        return jdbc.queryForObject(
                "SELECT payment_group_id FROM payment_delivery_request WHERE id=?", Long.class, id);
    }

    public String refundStatus(long groupId) {
        var rows =
                jdbc.queryForList(
                        "SELECT status FROM payment_relay_refund WHERE payment_group_id=?",
                        String.class,
                        groupId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requireRefund(PaymentGroup group, Approved approved) {
        String key = "late-" + approved.paymentId();
        var rows =
                jdbc.queryForList(
                        "SELECT payment_group_id,amount FROM payment_relay_refund WHERE refund_request_id=?",
                        key);
        if (!rows.isEmpty()) {
            if (((Number) rows.getFirst().get("payment_group_id")).longValue() != group.getId()
                    || ((Number) rows.getFirst().get("amount")).longValue() != approved.amount())
                throw conflict("늦은 승인 환불 원문이 다릅니다.");
            return false;
        }
        jdbc.update(
                "INSERT INTO payment_relay_refund(refund_request_id,payment_group_id,remote_payment_id,amount,reason,status,approved_at,requested_at) VALUES(?,?,?,?,'LATE_APPROVAL','REQUESTED',?,CURRENT_TIMESTAMP)",
                key,
                group.getId(),
                approved.paymentId(),
                approved.amount(),
                Timestamp.from(approved.approvedAt()));
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refund(
            long groupId, String key, String status, Instant completedAt, String failureCode) {
        var rows =
                jdbc.queryForList(
                        "SELECT status FROM payment_relay_refund WHERE refund_request_id=? AND payment_group_id=?",
                        String.class,
                        key,
                        groupId);
        if (rows.isEmpty()) throw conflict("저장된 환불 요청이 없습니다.");
        String previous = rows.getFirst();
        if (!"REQUESTED".equals(previous)) {
            if (!status.equals(previous)) throw conflict("이미 확정한 환불 결과와 다릅니다.");
            return false;
        }
        jdbc.update(
                "UPDATE payment_relay_refund SET status=?,completed_at=?,failure_code=? WHERE refund_request_id=? AND status='REQUESTED'",
                status,
                completedAt == null ? null : Timestamp.from(completedAt),
                failureCode,
                key);
        return true;
    }

    public record Binding(
            long groupId, Long paymentId, String pgOrderId, String status, Instant approvedAt) {}

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
