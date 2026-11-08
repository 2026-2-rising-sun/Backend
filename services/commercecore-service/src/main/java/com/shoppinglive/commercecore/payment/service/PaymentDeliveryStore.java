package com.shoppinglive.commercecore.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.*;
import com.shoppinglive.common.core.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class PaymentDeliveryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PaymentDeliveryStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentExecution payment(
            Long orderId,
            Long groupId,
            String number,
            long amount,
            String key,
            PaymentScenario scenario) {
        String requestId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        long id =
                insert(
                        requestId, "PAYMENT", orderId, groupId, number, amount, key, null, null,
                        now);
        var command = new PaymentCommand(requestId, id, orderId, groupId, number, amount, scenario);
        jdbc.update(
                "UPDATE payment_delivery_request SET payment_id=?,command=? WHERE id=?",
                id,
                json(command),
                id);
        return new PaymentExecution(
                requestId,
                id,
                orderId,
                groupId,
                number,
                amount,
                scenario == null ? PaymentScenario.INSTANT_SUCCESS : scenario,
                PaymentStatus.PROCESSING,
                now,
                null,
                now.plus(
                        (scenario == null ? PaymentScenario.INSTANT_SUCCESS : scenario)
                                .getDelay()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(
            long refundId, Long paymentId, Long orderId, Long groupId, String number, long amount) {
        if (!jdbc.queryForList(
                        "SELECT id FROM payment_delivery_request WHERE core_refund_request_id=?",
                        Long.class,
                        refundId)
                .isEmpty()) return;
        String requestId = UUID.randomUUID().toString();
        long id =
                insert(
                        requestId,
                        "REFUND",
                        orderId,
                        groupId,
                        number,
                        amount,
                        "refund-" + refundId,
                        paymentId,
                        refundId,
                        Instant.now());
        jdbc.update(
                "UPDATE payment_delivery_request SET command=? WHERE id=?",
                json(
                        new RefundCommand(
                                requestId, refundId, paymentId, orderId, groupId, number, amount)),
                id);
    }

    private long insert(
            String requestId,
            String kind,
            Long orderId,
            Long groupId,
            String number,
            long amount,
            String key,
            Long paymentId,
            Long refundId,
            Instant now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    var p =
                            connection.prepareStatement(
                                    "INSERT INTO payment_delivery_request(request_id,kind,order_id,payment_group_id,reference_number,amount,request_key,payment_id,core_refund_request_id,command,status,requested_at,next_action_at) VALUES (?,?,?,?,?,?,?,?,?,'','PROCESSING',?,?)",
                                    new String[] {"id"});
                    Object[] values = {
                        requestId,
                        kind,
                        orderId,
                        groupId,
                        number,
                        amount,
                        key,
                        paymentId,
                        refundId,
                        Timestamp.from(now),
                        Timestamp.from(now)
                    };
                    for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
                    return p;
                },
                holder);
        return holder.getKey().longValue();
    }

    public PaymentExecution payment(long id) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM payment_delivery_request WHERE kind='PAYMENT' AND payment_id=?",
                        id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "결제를 찾을 수 없습니다.");
        var row = rows.getFirst();
        if (row.get("result") != null)
            return read((String) row.get("result"), PaymentExecution.class);
        var c = read((String) row.get("command"), PaymentCommand.class);
        Instant at = instant(row.get("requested_at"));
        return new PaymentExecution(
                c.requestId(),
                c.paymentId(),
                c.orderId(),
                c.paymentGroupId(),
                c.referenceNumber(),
                c.amount(),
                c.scenario() == null ? PaymentScenario.INSTANT_SUCCESS : c.scenario(),
                PaymentStatus.PROCESSING,
                at,
                null,
                at.plus(
                        (c.scenario() == null ? PaymentScenario.INSTANT_SUCCESS : c.scenario())
                                .getDelay()));
    }

    public String key(long id) {
        return jdbc.queryForObject(
                "SELECT request_key FROM payment_delivery_request WHERE kind='PAYMENT' AND payment_id=?",
                String.class,
                id);
    }

    public Delivery get(String requestId) {
        var rows =
                jdbc.query(
                        "SELECT id,request_id,kind,command,result,status FROM payment_delivery_request WHERE request_id=?",
                        (r, n) ->
                                new Delivery(
                                        r.getLong(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4),
                                        r.getString(5),
                                        r.getString(6)),
                        requestId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT, "저장된 전달 요청이 없습니다.");
        return rows.getFirst();
    }

    public Delivery byPayment(long id) {
        return get(
                jdbc.queryForObject(
                        "SELECT request_id FROM payment_delivery_request WHERE kind='PAYMENT' AND payment_id=?",
                        String.class,
                        id));
    }

    public Delivery byRefund(long id) {
        return get(
                jdbc.queryForObject(
                        "SELECT request_id FROM payment_delivery_request WHERE core_refund_request_id=?",
                        String.class,
                        id));
    }

    public List<Delivery> due() {
        return jdbc.query(
                "SELECT id,request_id,kind,command,result,status FROM payment_delivery_request WHERE status IN ('PROCESSING','UNKNOWN') AND next_action_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 100",
                (r, n) ->
                        new Delivery(
                                r.getLong(1),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getString(6)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Delivery d, Object result, String status) {
        // 확정 결과를 늦게 도착한 PROCESSING/UNKNOWN 응답으로 덮어쓰지 않는다.
        jdbc.update(
                "UPDATE payment_delivery_request SET result=?,status=?,next_action_at=? WHERE id=? AND status IN ('PROCESSING','UNKNOWN')",
                json(result),
                status,
                Timestamp.from(Instant.now().plusSeconds(1)),
                d.id());
    }

    public void delay(Delivery d) {
        jdbc.update(
                "UPDATE payment_delivery_request SET next_action_at=? WHERE id=?",
                Timestamp.from(Instant.now().plusSeconds(1)),
                d.id());
    }

    public void requireSameResult(Delivery d, Object result) {
        if (!Objects.equals(readTree(d.result()), readTree(json(result))))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 반영한 확정 결과와 다릅니다.");
    }

    private com.fasterxml.jackson.databind.JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("전달 DTO 직렬화 실패", e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("전달 DTO 역직렬화 실패", e);
        }
    }

    private static Instant instant(Object value) {
        return value instanceof java.time.OffsetDateTime d
                ? d.toInstant()
                : ((Timestamp) value).toInstant();
    }

    public record Delivery(
            long id, String requestId, String kind, String command, String result, String status) {
        public boolean terminal() {
            return "SUCCESS".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status);
        }
    }
}
