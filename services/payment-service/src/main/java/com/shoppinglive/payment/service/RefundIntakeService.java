package com.shoppinglive.payment.service;

import com.shoppinglive.common.core.*;
import com.shoppinglive.payment.dto.*;
import com.shoppinglive.payment.entity.*;
import com.shoppinglive.payment.support.AfterCommit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RefundIntakeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RefundMockEngine engine;
    private final boolean executionEnabled;
    private final OrderClosureService closures;

    public RefundIntakeService(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            RefundMockEngine engine,
            boolean executionEnabled) {
        this(
                jdbc,
                tx,
                engine,
                new OrderClosureService(jdbc, tx.getTransactionManager()),
                executionEnabled);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RefundIntakeService(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            RefundMockEngine engine,
            OrderClosureService closures,
            @org.springframework.beans.factory.annotation.Value(
                            "${payment.refunds.execution-enabled:false}")
                    boolean executionEnabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.engine = engine;
        this.closures = closures;
        this.executionEnabled = executionEnabled;
    }

    public RefundExecution start(RefundCommand c) {
        PaymentService.requireUuid(c.requestId());
        closures.requireCompensation(c);
        try {
            return tx.execute(
                    t -> {
                        // 한 원결제의 접수/확정은 같은 행을 먼저 잠가 누적 한도를 직렬화한다.
                        var payment =
                                jdbc.queryForList(
                                        "SELECT * FROM payment_attempt WHERE payment_id=? FOR UPDATE",
                                        c.paymentId());
                        if (payment.isEmpty())
                            throw new BusinessException(ErrorCode.NOT_FOUND, "원결제를 찾을 수 없습니다.");
                        var p = payment.getFirst();
                        if (!Objects.equals(p.get("order_id"), c.orderId())
                                || !Objects.equals(p.get("payment_group_id"), c.paymentGroupId())
                                || !Objects.equals(p.get("reference_number"), c.referenceNumber())
                                || !"SUCCESS".equals(p.get("status")))
                            throw new BusinessException(ErrorCode.CONFLICT, "환불 원결제 대상이 다릅니다.");
                        var existing = findExisting(c);
                        if (existing != null) return existing;
                        long total =
                                jdbc.queryForObject(
                                        "SELECT COALESCE(SUM(refund_amount),0) FROM refund_execution WHERE payment_id=? AND status IN ('PROCESSING','UNKNOWN','SUCCESS')",
                                        Long.class,
                                        c.paymentId());
                        long paid = ((Number) p.get("amount")).longValue();
                        if (c.amount() > paid || total > paid - c.amount())
                            throw new BusinessException(
                                    ErrorCode.CONFLICT, "누적 환불 금액이 결제 금액을 초과합니다.");
                        // 선점과 같은 DB 시각을 사용해 JVM 시계가 앞설 때 초기 예약이 지연되지 않게 한다.
                        Instant now =
                                jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class)
                                        .toInstant();
                        jdbc.update(
                                "INSERT INTO refund_execution(id,request_id,core_refund_request_id,payment_id,order_id,payment_group_id,reference_number,refund_amount,status,idempotency_key,request_fingerprint,requested_at,created_at,updated_at,next_action_at) VALUES (?,?,?,?,?,?,?,?,'PROCESSING',?,?,?,?,?,?)",
                                c.coreRefundRequestId(),
                                c.requestId(),
                                c.coreRefundRequestId(),
                                c.paymentId(),
                                c.orderId(),
                                c.paymentGroupId(),
                                c.referenceNumber(),
                                c.amount(),
                                c.requestId(),
                                c.requestId(),
                                Timestamp.from(now),
                                Timestamp.from(now),
                                Timestamp.from(now),
                                Timestamp.from(now));
                        if (c.orderClosureRequestId() != null)
                            jdbc.update(
                                    "UPDATE refund_execution SET order_closure_request_id=? WHERE id=?",
                                    c.orderClosureRequestId(),
                                    c.coreRefundRequestId());
                        if (executionEnabled || c.orderClosureRequestId() != null)
                            AfterCommit.run(() -> engine.schedule(c.coreRefundRequestId()));
                        return get(c.coreRefundRequestId());
                    });
        } catch (DataIntegrityViolationException race) {
            var p = findExisting(c);
            if (p == null) throw race;
            return p;
        }
    }

    private RefundExecution findExisting(RefundCommand c) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM refund_execution WHERE request_id=? OR core_refund_request_id=? OR (payment_id=? AND order_closure_request_id IS NOT NULL AND CAST(? AS VARCHAR) IS NOT NULL)",
                        Long.class,
                        c.requestId(),
                        c.coreRefundRequestId(),
                        c.paymentId(),
                        c.orderClosureRequestId());
        if (ids.isEmpty()) return null;
        var p = get(ids.getFirst());
        if (ids.size() != 1
                || !Objects.equals(p.requestId(), c.requestId())
                || !Objects.equals(p.coreRefundRequestId(), c.coreRefundRequestId())
                || !Objects.equals(p.paymentId(), c.paymentId())
                || !Objects.equals(p.orderId(), c.orderId())
                || !Objects.equals(p.paymentGroupId(), c.paymentGroupId())
                || !Objects.equals(p.referenceNumber(), c.referenceNumber())
                || p.amount() != c.amount()
                || !Objects.equals(
                        jdbc.queryForObject(
                                "SELECT order_closure_request_id FROM refund_execution WHERE id=?",
                                String.class,
                                p.executionId()),
                        c.orderClosureRequestId()))
            throw new BusinessException(ErrorCode.CONFLICT, "기존 환불 요청과 내용이 다릅니다.");
        return p;
    }

    public RefundExecution get(long id) {
        var ids = jdbc.queryForList("SELECT id FROM refund_execution WHERE id=?", Long.class, id);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 실행을 찾을 수 없습니다.");
        return RefundRows.get(jdbc, id);
    }
}
