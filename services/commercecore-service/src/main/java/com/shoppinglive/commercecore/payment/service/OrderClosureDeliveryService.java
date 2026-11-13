package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.payment.dto.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderClosureDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(OrderClosureDeliveryService.class);
    private final JdbcTemplate jdbc;
    private final PaymentDeliveryStore deliveries;
    private final PaymentClient client;
    private final com.shoppinglive.commercecore.order.service.OrderTimeSource time;

    public OrderClosureDeliveryService(
            JdbcTemplate jdbc,
            PaymentDeliveryStore deliveries,
            PaymentClient client,
            com.shoppinglive.commercecore.order.service.OrderTimeSource time) {
        this.jdbc = jdbc;
        this.deliveries = deliveries;
        this.client = client;
        this.time = time;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordExpired(PaymentGroup group) {
        if (group.getPaymentId() == null) return;
        if (!jdbc.queryForList(
                        "SELECT closure_request_id FROM order_payment_closure WHERE payment_id=?",
                        String.class,
                        group.getPaymentId())
                .isEmpty()) return;
        var delivery = deliveries.byPayment(group.getPaymentId());
        var payment = deliveries.read(delivery.command(), PaymentCommand.class);
        Instant now = time.now();
        jdbc.update(
                "INSERT INTO order_payment_closure(closure_request_id,payment_request_id,payment_id,order_id,payment_group_id,reference_number,amount,reason,closed_at,acknowledged,next_action_at) VALUES (?,?,?,?,?,?,?,'EXPIRED',?,FALSE,?)",
                UUID.randomUUID().toString(),
                payment.requestId(),
                payment.paymentId(),
                payment.orderId(),
                payment.paymentGroupId(),
                payment.referenceNumber(),
                payment.amount(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void compensate(PaymentExecution payment) {
        // 0원 성공은 과금이 없으므로 PG 환불 요청을 만들지 않는다.
        if (payment.amount() == 0) return;
        var closure =
                jdbc.queryForMap(
                        "SELECT * FROM order_payment_closure WHERE payment_id=?",
                        payment.paymentId());
        String closureId = (String) closure.get("closure_request_id");
        if (!jdbc.queryForList(
                        "SELECT id FROM refund_request WHERE order_closure_request_id=?",
                        Long.class,
                        closureId)
                .isEmpty()) return;
        String member =
                jdbc.queryForObject(
                        "SELECT member_id FROM payment_group WHERE id=?",
                        String.class,
                        payment.paymentGroupId());
        Instant now = time.now();
        var holder = new GeneratedKeyHolder();
        jdbc.update(
                c -> {
                    var s =
                            c.prepareStatement(
                                    "INSERT INTO refund_request(payment_group_id,member_id,idempotency_key,request_fingerprint,refund_amount,status,requested_at,created_at,updated_at,purpose,order_closure_request_id) VALUES (?,?,?,?,?,'PROCESSING',?,?,?,'COMPENSATION',?)",
                                    new String[] {"id"});
                    s.setLong(1, payment.paymentGroupId());
                    s.setString(2, member);
                    s.setString(3, "closure:" + closureId);
                    s.setString(4, payment.requestId());
                    s.setLong(5, payment.amount());
                    s.setTimestamp(6, Timestamp.from(now));
                    s.setTimestamp(7, Timestamp.from(now));
                    s.setTimestamp(8, Timestamp.from(now));
                    s.setString(9, closureId);
                    return s;
                },
                holder);
        long refundId = Objects.requireNonNull(holder.getKey()).longValue();
        deliveries.compensatingRefund(
                refundId,
                payment.paymentId(),
                payment.orderId(),
                payment.paymentGroupId(),
                payment.referenceNumber(),
                payment.amount(),
                closureId);
    }

    @Scheduled(
            initialDelayString = "${commercecore.order.closure-recovery.initial-delay:PT1S}",
            fixedDelayString = "PT1S")
    public void recover() {
        var pending =
                jdbc.query(
                        "SELECT * FROM order_payment_closure WHERE acknowledged=FALSE AND next_action_at<=CURRENT_TIMESTAMP ORDER BY next_action_at,payment_id LIMIT 100",
                        (r, n) ->
                                new Pending(
                                        r.getLong("payment_id"),
                                        new OrderClosureCommand(
                                                r.getString("closure_request_id"),
                                                r.getString("payment_request_id"),
                                                r.getLong("order_id"),
                                                r.getLong("payment_group_id"),
                                                r.getString("reference_number"),
                                                r.getLong("amount"),
                                                r.getString("reason"),
                                                r.getTimestamp("closed_at").toInstant())));
        for (var p : pending) {
            try {
                client.closeOrder(p.paymentId(), p.command());
                jdbc.update(
                        "UPDATE order_payment_closure SET acknowledged=TRUE WHERE closure_request_id=?",
                        p.command().closureRequestId());
            } catch (RuntimeException unavailable) {
                jdbc.update(
                        "UPDATE order_payment_closure SET next_action_at=? WHERE closure_request_id=?",
                        Timestamp.from(Instant.now().plusSeconds(1)),
                        p.command().closureRequestId());
                log.warn("주문 종료 통지 재시도 예정: paymentId={}", p.paymentId());
            }
        }
    }

    private record Pending(long paymentId, OrderClosureCommand command) {}
}
