package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.service.PaymentGroupService;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commercecore.sales.service.SalesService;
import com.shoppinglive.common.core.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentResultHandler {
    private final PaymentDeliveryStore store;
    private final ObjectProvider<PaymentGroupService> groups;
    private final RefundOutcomeService refunds;
    private final TransactionTemplate tx;
    private final OrderJpaRepository orders;
    private final SalesStockJpaRepository stock;
    private final SalesService sales;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PaymentResultHandler(
            PaymentDeliveryStore store,
            ObjectProvider<PaymentGroupService> groups,
            RefundOutcomeService refunds,
            TransactionTemplate tx,
            OrderJpaRepository orders,
            SalesStockJpaRepository stock,
            SalesService sales,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.store = store;
        this.groups = groups;
        this.refunds = refunds;
        this.tx = tx;
        this.orders = orders;
        this.stock = stock;
        this.sales = sales;
        this.jdbc = jdbc;
    }

    public void payment(PaymentExecution p) {
        require(p != null, "결제 결과가 필요합니다.");
        var d = store.get(p.requestId());
        require("PAYMENT".equals(d.kind()), "결과 종류가 다릅니다.");
        var c = store.read(d.command(), PaymentCommand.class);
        require(
                Objects.equals(c.paymentId(), p.paymentId())
                        && Objects.equals(c.orderId(), p.orderId())
                        && Objects.equals(c.paymentGroupId(), p.paymentGroupId())
                        && Objects.equals(c.referenceNumber(), p.referenceNumber())
                        && c.amount() == p.amount()
                        && (c.scenario() == null || c.scenario() == p.scenario()),
                "결제 결과가 전달 원문과 다릅니다.");
        require(
                p.status() != null
                        && (p.status().isUnconfirmed() || p.status().isConfirmedOutcome()),
                "지원하지 않는 결제 결과입니다.");
        times(p.requestedAt(), p.resolvedAt(), p.status().isConfirmedOutcome());
        require(
                p.scheduledResolveAt() != null && !p.scheduledResolveAt().isBefore(p.requestedAt()),
                "결제 예정 시각이 올바르지 않습니다.");
        if (d.terminal()) {
            if (p.resolvedAt() != null) store.requireSameResult(d, p);
            return;
        }
        if (!p.status().isConfirmedOutcome()) {
            tx.executeWithoutResult(t -> store.record(d, p, p.status().name()));
            return;
        }
        if (p.paymentGroupId() != null) groups.getObject().applyOutcome(p);
        else
            tx.executeWithoutResult(
                    t -> {
                        jdbc.queryForObject(
                                "SELECT id FROM orders WHERE id=? FOR UPDATE",
                                Long.class,
                                p.orderId());
                        var current = store.get(p.requestId());
                        if (current.terminal()) {
                            store.requireSameResult(current, p);
                            return;
                        }
                        var order = orders.findById(p.orderId()).orElseThrow();
                        boolean success =
                                p.status()
                                        == com.shoppinglive.commercecore.payment.entity
                                                .PaymentStatus.SUCCESS;
                        if (orders.transitionStatus(
                                        order.getId(),
                                        "PAYMENT_CONFIRMING",
                                        success ? "PAID" : "FAILED")
                                != 1) throw new IllegalStateException("단건 결제 주문 상태가 다릅니다.");
                        if (success) {
                            if (stock.consumeReserved(order.getSalesInfoId(), order.getQuantity())
                                    != 1) throw new IllegalStateException("예약 재고가 누락되었습니다.");
                        } else sales.restoreReserved(order.getSalesInfoId(), order.getQuantity());
                        store.record(current, p, p.status().name());
                    });
    }

    public void refund(RefundExecution p) {
        require(p != null, "환불 결과가 필요합니다.");
        var d = store.get(p.requestId());
        require("REFUND".equals(d.kind()), "결과 종류가 다릅니다.");
        var c = store.read(d.command(), RefundCommand.class);
        require(
                Objects.equals(p.executionId(), c.coreRefundRequestId())
                        && Objects.equals(p.coreRefundRequestId(), c.coreRefundRequestId())
                        && Objects.equals(p.paymentId(), c.paymentId())
                        && Objects.equals(p.orderId(), c.orderId())
                        && Objects.equals(p.paymentGroupId(), c.paymentGroupId())
                        && Objects.equals(p.referenceNumber(), c.referenceNumber())
                        && p.amount() == c.amount(),
                "환불 결과가 전달 원문과 다릅니다.");
        require(
                p.status() != null
                        && p.retryCount() >= 0
                        && p.retryCount() <= 3
                        && p.nextActionAt() != null,
                "환불 상태와 복구 정보가 올바르지 않습니다.");
        boolean terminal =
                p.status() == com.shoppinglive.commercecore.payment.entity.RefundStatus.SUCCESS
                        || p.status()
                                == com.shoppinglive.commercecore.payment.entity.RefundStatus.FAILED;
        times(p.requestedAt(), p.resolvedAt(), terminal);
        if (d.terminal()) {
            if (p.resolvedAt() != null) store.requireSameResult(d, p);
            return;
        }
        if (terminal) refunds.apply(p);
        else
            tx.executeWithoutResult(
                    t -> {
                        store.record(d, p, p.status().name());
                        jdbc.update(
                                "UPDATE refund_request SET status=?,retry_count=?,next_action_at=? WHERE id=? AND status IN ('PROCESSING','UNKNOWN')",
                                p.status().name(),
                                p.retryCount(),
                                java.sql.Timestamp.from(p.nextActionAt()),
                                p.coreRefundRequestId());
                    });
    }

    private static void times(Instant requested, Instant resolved, boolean terminal) {
        require(
                requested != null
                        && ((terminal && resolved != null && !resolved.isBefore(requested))
                                || (!terminal && resolved == null)),
                "결과 시각이 상태와 일치하지 않습니다.");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException(ErrorCode.CONFLICT, message);
    }
}
