package com.shoppinglive.commercecore.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.order.service.OrderTimeSource;
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
    private final OrderTimeSource time;

    public PaymentDeliveryStore(JdbcTemplate jdbc, ObjectMapper mapper, OrderTimeSource time) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.time = time;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public Delivery relay(
            String kind,
            Long orderId,
            Long groupId,
            String number,
            long amount,
            String key,
            Long paymentId,
            Object command) {
        if (!"PREPARE".equals(kind) && !"APPROVAL".equals(kind))
            throw new IllegalArgumentException("지원하지 않는 중계 종류입니다.");
        // 승인은 시도마다 한 건이다. 같은 키는 그 시도를, 다른 키는 진행 중이거나 성공한 시도를 가리킨다.
        var existing =
                "APPROVAL".equals(kind)
                        ? jdbc.queryForList(
                                "SELECT request_id FROM payment_delivery_request WHERE payment_group_id=? AND kind='APPROVAL' AND (request_key=? OR status<>'FAILED') ORDER BY (request_key=?) DESC, id DESC",
                                String.class,
                                groupId,
                                key,
                                key)
                        : jdbc.queryForList(
                                "SELECT request_id FROM payment_delivery_request WHERE payment_group_id=? AND kind=?",
                                String.class,
                                groupId,
                                kind);
        if (!existing.isEmpty()) {
            Delivery prior = get(existing.getFirst());
            String priorKey =
                    jdbc.queryForObject(
                            "SELECT request_key FROM payment_delivery_request WHERE id=?",
                            String.class,
                            prior.id());
            if (!Objects.equals(key, priorKey)
                    || !Objects.equals(readTree(json(command)), readTree(prior.command())))
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "APPROVAL".equals(kind)
                                        && Objects.equals(key, priorKey)
                                        && "FAILED".equals(prior.status())
                                ? "거절된 승인 시도의 키입니다. 재결제는 새 X-Idempotency-Key로 요청해 주세요."
                                : "기존 중계 요청과 입력이 다릅니다.");
            return prior;
        }
        String requestId = UUID.randomUUID().toString();
        long id =
                insert(
                        requestId,
                        kind,
                        orderId,
                        groupId,
                        number,
                        amount,
                        key,
                        paymentId,
                        null,
                        time.now());
        jdbc.update("UPDATE payment_delivery_request SET command=? WHERE id=?", json(command), id);
        return get(requestId);
    }

    public boolean approvalKeyUsed(long groupId, String key) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_delivery_request WHERE payment_group_id=? AND kind='APPROVAL' AND request_key=?",
                        Long.class,
                        groupId,
                        key)
                > 0;
    }

    /** 가장 최근 시도를 돌려준다. 승인은 거절된 이전 시도가 남을 수 있다. */
    public Delivery relay(Long groupId, String kind) {
        var ids =
                jdbc.queryForList(
                        "SELECT request_id FROM payment_delivery_request WHERE payment_group_id=? AND kind=? ORDER BY id DESC LIMIT 1",
                        String.class,
                        groupId,
                        kind);
        return ids.isEmpty() ? null : get(ids.getFirst());
    }

    public java.util.List<Delivery> relayDue() {
        return jdbc.query(
                "SELECT id,request_id,kind,command,result,status FROM payment_delivery_request WHERE kind IN ('PREPARE','APPROVAL') AND status IN ('PROCESSING','UNKNOWN') AND next_action_at<=CURRENT_TIMESTAMP ORDER BY next_action_at,id LIMIT 100",
                (r, n) ->
                        new Delivery(
                                r.getLong(1),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getString(6)));
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Delivery d, Object result, String status) {
        // 확정 결과를 늦게 도착한 PROCESSING/UNKNOWN 응답으로 덮어쓰지 않는다.
        jdbc.update(
                "UPDATE payment_delivery_request SET result=?,status=?,next_action_at=? WHERE id=? AND status IN ('PROCESSING','UNKNOWN')",
                json(result),
                status,
                Timestamp.from(time.now().plusSeconds(1)),
                d.id());
    }

    public void delay(Delivery d) {
        jdbc.update(
                "UPDATE payment_delivery_request SET next_action_at=? WHERE id=?",
                Timestamp.from(time.now().plusSeconds(1)),
                d.id());
    }

    public void requireSameResult(Delivery d, Object result) {
        if (!Objects.equals(comparable(d.result()), comparable(json(result))))
            throw new BusinessException(ErrorCode.CONFLICT, "이미 반영한 확정 결과와 다릅니다.");
    }

    private com.fasterxml.jackson.databind.JsonNode comparable(String value) {
        var node = readTree(value);
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            object.remove("retryCount");
            object.remove("nextActionAt");
        }
        return node;
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
