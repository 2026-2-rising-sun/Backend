package com.shoppinglive.payment.service;

import com.shoppinglive.common.core.*;
import com.shoppinglive.payment.dto.*;
import com.shoppinglive.payment.entity.*;
import com.shoppinglive.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.payment.service.PaymentRecoveryStore.Lease;
import com.shoppinglive.payment.support.AfterCommit;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private final PaymentAttemptJpaRepository attempts;
    private final PaymentRecoveryStore recovery;
    private final DurableMockGateway gateway;
    private final PaymentRetryPolicy retryPolicy;
    private final MockPaymentEngine engine;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final ExecutionResultPublisher events;
    private final org.springframework.beans.factory.ObjectProvider<DevPaymentScenarioRegistry>
            scenarios;

    public PaymentService(
            PaymentAttemptJpaRepository attempts,
            PaymentRecoveryStore recovery,
            DurableMockGateway gateway,
            PaymentRetryPolicy retryPolicy,
            MockPaymentEngine engine,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            ExecutionResultPublisher events,
            org.springframework.beans.factory.ObjectProvider<DevPaymentScenarioRegistry>
                    scenarios) {
        this.attempts = attempts;
        this.recovery = recovery;
        this.gateway = gateway;
        this.retryPolicy = retryPolicy;
        this.engine = engine;
        this.tx = tx;
        this.jdbc = jdbc;
        this.events = events;
        this.scenarios = scenarios;
    }

    public PaymentExecution start(PaymentCommand command) {
        requireUuid(command.requestId());
        try {
            return tx.execute(
                    status -> {
                        var existing = existing(command);
                        if (existing != null) return existing;
                        var attempt =
                                new PaymentAttempt(
                                        command.orderId(),
                                        scenario(command),
                                        jdbc.queryForObject(
                                                        "SELECT CURRENT_TIMESTAMP",
                                                        java.sql.Timestamp.class)
                                                .toInstant());
                        attempt.identify(
                                command.requestId(),
                                command.paymentId(),
                                command.paymentGroupId(),
                                command.referenceNumber(),
                                command.amount());
                        if (command.amount() == 0)
                            attempt.completeWithoutCharge(attempt.getRequestedAt());
                        attempts.saveAndFlush(attempt);
                        var result = getByAttemptId(attempt.getId());
                        if (command.amount() == 0) events.payment(result);
                        else
                            AfterCommit.run(
                                    () -> engine.schedule(attempt.getId(), attempt.getScenario()));
                        return result;
                    });
        } catch (DataIntegrityViolationException race) {
            // 유일키 충돌 뒤 재조회는 실패한 삽입 트랜잭션 밖에서 한다.
            var existing = existing(command);
            if (existing == null) throw race;
            return existing;
        }
    }

    private PaymentExecution existing(PaymentCommand c) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM payment_attempt WHERE request_id=? OR payment_id=?",
                        Long.class,
                        c.requestId(),
                        c.paymentId());
        if (ids.isEmpty()) return null;
        var p = getByAttemptId(ids.getFirst());
        if (ids.size() != 1
                || !Objects.equals(p.requestId(), c.requestId())
                || !Objects.equals(p.paymentId(), c.paymentId())
                || !Objects.equals(p.orderId(), c.orderId())
                || !Objects.equals(p.paymentGroupId(), c.paymentGroupId())
                || !Objects.equals(p.referenceNumber(), c.referenceNumber())
                || p.amount() != c.amount()
                || (c.scenario() != null && p.scenario() != c.scenario()))
            throw new BusinessException(ErrorCode.CONFLICT, "기존 결제 요청과 내용이 다릅니다.");
        return p;
    }

    public PaymentExecution get(long paymentId) {
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM payment_attempt WHERE payment_id=?", Long.class, paymentId);
        if (ids.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "결제를 찾을 수 없습니다.");
        return getByAttemptId(ids.getFirst());
    }

    public boolean resolvePayment(Long id) {
        var lease = recovery.claim(id, Duration.ofMinutes(1));
        return lease.isPresent() && resolveClaimed(lease.get());
    }

    public boolean resolveClaimed(Lease lease) {
        int count = lease.retryCount();
        try {
            PaymentStatus result = gateway.find(lease.id());
            if (result == null) {
                var invocation = recovery.beginInvocation(lease);
                if (invocation.isEmpty()) {
                    recovery.unknown(lease, PaymentRetryPolicy.RESULT_QUERY_DELAY);
                    return false;
                }
                count = invocation.get();
                try {
                    result = gateway.authorize(lease.id(), lease.scenario());
                } catch (MockPaymentResultUnknownException unknown) {
                    recovery.unknown(lease, retryPolicy.afterUnknown(count));
                    return false;
                }
            }
            if (!result.isConfirmedOutcome()) {
                recovery.unknown(lease, retryPolicy.afterUnknown(count));
                return false;
            }
            final PaymentStatus outcome = result;
            return tx.execute(
                    status -> {
                        if (!recovery.complete(lease, outcome.name())) return false;
                        // JDBC 확정 뒤 JPA의 기존 관리 객체를 재사용하지 않는다.
                        var p = getByAttemptId(lease.id());
                        events.payment(p);
                        return true;
                    });
        } catch (RuntimeException failure) {
            recovery.unknown(lease, retryPolicy.afterUnknown(count));
            throw failure;
        }
    }

    private PaymentExecution getByAttemptId(long id) {
        return jdbc.queryForObject(
                "SELECT request_id,payment_id,order_id,payment_group_id,reference_number,amount,scenario,status,requested_at,resolved_at,scheduled_resolve_at FROM payment_attempt WHERE id=?",
                (r, n) ->
                        new PaymentExecution(
                                r.getString(1),
                                r.getLong(2),
                                r.getLong(3),
                                (Long) r.getObject(4),
                                r.getString(5),
                                r.getLong(6),
                                PaymentScenario.valueOf(r.getString(7)),
                                PaymentStatus.valueOf(r.getString(8)),
                                r.getTimestamp(9).toInstant(),
                                r.getTimestamp(10) == null ? null : r.getTimestamp(10).toInstant(),
                                r.getTimestamp(11).toInstant()),
                id);
    }

    private PaymentScenario scenario(PaymentCommand command) {
        if (command.scenario() != null) return command.scenario();
        var registry = scenarios.getIfAvailable();
        return registry == null
                ? PaymentScenario.INSTANT_SUCCESS
                : registry.get(command.referenceNumber()).orElse(PaymentScenario.INSTANT_SUCCESS);
    }

    static void requireUuid(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "requestId는 표준 UUID여야 합니다.");
        }
    }
}
