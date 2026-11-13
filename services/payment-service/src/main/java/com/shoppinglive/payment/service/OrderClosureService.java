package com.shoppinglive.payment.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.payment.dto.OrderClosureCommand;
import com.shoppinglive.payment.dto.PaymentCommand;
import com.shoppinglive.payment.dto.RefundCommand;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderClosureService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate independent;

    public OrderClosureService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensure(long paymentId, String paymentRequestId) {
        PaymentService.requireUuid(paymentRequestId);
        try {
            independent.executeWithoutResult(
                    t ->
                            jdbc.update(
                                    "INSERT INTO payment_order_control(payment_id,payment_request_id) SELECT ?,? WHERE NOT EXISTS (SELECT 1 FROM payment_order_control WHERE payment_id=?)",
                                    paymentId,
                                    paymentRequestId,
                                    paymentId));
        } catch (DataIntegrityViolationException race) {
            // 실패한 삽입 트랜잭션 밖에서 먼저 저장된 실행 키를 대조한다.
        }
        var keys =
                jdbc.queryForList(
                        "SELECT payment_request_id FROM payment_order_control WHERE payment_id=?",
                        String.class,
                        paymentId);
        require(
                keys.size() == 1 && Objects.equals(keys.getFirst(), paymentRequestId),
                "다른 결제 실행 키에 사용된 ID입니다.");
    }

    public Map<String, Object> lock(long paymentId, String requestId) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM payment_order_control WHERE payment_id=? FOR UPDATE",
                        paymentId);
        require(Objects.equals(row.get("payment_request_id"), requestId), "결제 실행 키가 다릅니다.");
        return row;
    }

    public boolean lockClosedAttempt(long attemptId) {
        var attempt =
                jdbc.queryForMap(
                        "SELECT payment_id,request_id FROM payment_attempt WHERE id=?", attemptId);
        long paymentId = ((Number) attempt.get("payment_id")).longValue();
        String requestId = (String) attempt.get("request_id");
        ensure(paymentId, requestId);
        return lock(paymentId, requestId).get("closure_request_id") != null;
    }

    public boolean closed(PaymentCommand command) {
        Map<String, Object> row = lock(command.paymentId(), command.requestId());
        if (row.get("closure_request_id") == null) return false;
        require(
                samePayment(
                        row,
                        command.orderId(),
                        command.paymentGroupId(),
                        command.referenceNumber(),
                        command.amount()),
                "종료 통지의 결제 대상과 다릅니다.");
        return true;
    }

    public Receipt close(long paymentId, OrderClosureCommand command) {
        if (paymentId < 1)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 ID는 양수여야 합니다.");
        PaymentService.requireUuid(command.closureRequestId());
        ensure(paymentId, command.paymentRequestId());
        return tx.execute(
                t -> {
                    Map<String, Object> row = lock(paymentId, command.paymentRequestId());
                    var attempts =
                            jdbc.queryForList(
                                    "SELECT order_id,payment_group_id,reference_number,amount,request_id FROM payment_attempt WHERE payment_id=?",
                                    paymentId);
                    if (!attempts.isEmpty()) {
                        var p = attempts.getFirst();
                        require(
                                Objects.equals(p.get("request_id"), command.paymentRequestId())
                                        && samePayment(
                                                p,
                                                command.orderId(),
                                                command.paymentGroupId(),
                                                command.referenceNumber(),
                                                command.amount()),
                                "종료 통지의 원결제가 다릅니다.");
                    }
                    if (row.get("closure_request_id") != null) {
                        require(
                                Objects.equals(
                                                row.get("closure_request_id"),
                                                command.closureRequestId())
                                        && samePayment(
                                                row,
                                                command.orderId(),
                                                command.paymentGroupId(),
                                                command.referenceNumber(),
                                                command.amount())
                                        && Objects.equals(row.get("reason"), command.reason())
                                        && instant(row.get("closed_at"))
                                                .equals(
                                                        command.closedAt()
                                                                .truncatedTo(
                                                                        java.time.temporal
                                                                                .ChronoUnit
                                                                                .MICROS)),
                                "이미 다른 종료 통지가 기록됐습니다.");
                    } else
                        jdbc.update(
                                "UPDATE payment_order_control SET closure_request_id=?,order_id=?,payment_group_id=?,reference_number=?,amount=?,reason=?,closed_at=? WHERE payment_id=?",
                                command.closureRequestId(),
                                command.orderId(),
                                command.paymentGroupId(),
                                command.referenceNumber(),
                                command.amount(),
                                command.reason(),
                                Timestamp.from(
                                        command.closedAt()
                                                .truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
                                paymentId);
                    return new Receipt(
                            command.closureRequestId(),
                            command.paymentRequestId(),
                            paymentId,
                            "RECORDED");
                });
    }

    public void requireCompensation(RefundCommand command) {
        if (command.orderClosureRequestId() == null) return;
        PaymentService.requireUuid(command.orderClosureRequestId());
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM payment_order_control WHERE payment_id=?",
                        command.paymentId());
        if (rows.isEmpty() || rows.getFirst().get("closure_request_id") == null)
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "주문 종료 통지 반영 후 보상 환불을 다시 요청해 주세요.");
        var row = rows.getFirst();
        require(
                Objects.equals(row.get("closure_request_id"), command.orderClosureRequestId())
                        && samePayment(
                                row,
                                command.orderId(),
                                command.paymentGroupId(),
                                command.referenceNumber(),
                                command.amount()),
                "보상 환불의 종료 대상과 금액이 다릅니다.");
    }

    private static boolean samePayment(
            Map<String, Object> row, Long order, Long group, String reference, long amount) {
        return Objects.equals(row.get("order_id"), order)
                && Objects.equals(row.get("payment_group_id"), group)
                && Objects.equals(row.get("reference_number"), reference)
                && row.get("amount") instanceof Number n
                && n.longValue() == amount;
    }

    private static java.time.Instant instant(Object value) {
        return value instanceof java.time.OffsetDateTime d
                ? d.toInstant()
                : ((Timestamp) value).toInstant();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(ErrorCode.CONFLICT, message);
    }

    public record Receipt(
            String closureRequestId, String paymentRequestId, long paymentId, String status) {}
}
