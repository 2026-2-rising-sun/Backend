package com.shoppinglive.commerce.order.service;

import com.shoppinglive.commerce.coupon.service.CouponReservationService;
import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.entity.PaymentGroup;
import com.shoppinglive.commerce.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commerce.order.repository.CartItemRepository;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.order.repository.PaymentGroupRepository;
import com.shoppinglive.commerce.payment.dto.DevPaymentScenarioRegistry;
import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import com.shoppinglive.commerce.payment.repository.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.payment.service.MockPaymentEngine;
import com.shoppinglive.commerce.payment.service.PaymentRecoveryStore;
import com.shoppinglive.commerce.payment.service.PaymentRecoveryStore.Lease;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.commerce.support.AfterCommit;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PaymentGroupLifecycleService {
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final CartItemRepository cart;
    private final SalesJpaRepository sales;
    private final SalesStockJpaRepository stock;
    private final PaymentAttemptJpaRepository payments;
    private final PurchaseGuard guard;
    private final PaymentRecoveryStore recovery;
    private final MockPaymentEngine engine;
    private final ObjectProvider<DevPaymentScenarioRegistry> scenarios;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final CouponReservationService couponReservations;
    private final PaymentGroupEventPublisher notificationEvents;
    @PersistenceContext private EntityManager entityManager;

    PaymentGroupLifecycleService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            CartItemRepository cart,
            SalesJpaRepository sales,
            SalesStockJpaRepository stock,
            PaymentAttemptJpaRepository payments,
            PurchaseGuard guard,
            PaymentRecoveryStore recovery,
            MockPaymentEngine engine,
            ObjectProvider<DevPaymentScenarioRegistry> scenarios,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            CouponReservationService couponReservations,
            PaymentGroupEventPublisher notificationEvents) {
        this.groups = groups;
        this.orders = orders;
        this.cart = cart;
        this.sales = sales;
        this.stock = stock;
        this.payments = payments;
        this.guard = guard;
        this.recovery = recovery;
        this.engine = engine;
        this.scenarios = scenarios;
        this.tx = tx;
        this.jdbc = jdbc;
        this.couponReservations = couponReservations;
        this.notificationEvents = notificationEvents;
    }

    PaymentAttempt start(String member, String number, String key) {
        PaymentGroup found = owned(member, number);
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    PaymentGroup g = lockedGroup(found.getId());
                    if (g.getPaymentId() != null) {
                        PaymentAttempt previous = payments.findById(g.getPaymentId()).orElseThrow();
                        if (Objects.equals(previous.getRequestKey(), key)) {
                            return previous;
                        }
                        throw conflict("이미 결제를 시작했습니다. 기존 결과를 확인해 주세요.");
                    }
                    if (g.getStatus() != OrderStatus.PENDING_PAYMENT
                            || !g.getExpiresAt().isAfter(Instant.now())) {
                        throw conflict("결제를 시작할 수 없는 주문입니다.");
                    }
                    List<Order> children = orders.findByPaymentGroupIdOrderByIdAsc(g.getId());
                    DevPaymentScenarioRegistry registry = scenarios.getIfAvailable();
                    PaymentScenario scenario =
                            registry == null
                                    ? PaymentScenario.INSTANT_SUCCESS
                                    : registry.get(number)
                                            .orElseGet(
                                                    () ->
                                                            registry.get(
                                                                            children.getFirst()
                                                                                    .getOrderNumber())
                                                                    .orElse(
                                                                            PaymentScenario
                                                                                    .INSTANT_SUCCESS));
                    Instant requestedAt = Instant.now();
                    PaymentAttempt attempt =
                            new PaymentAttempt(children.getFirst().getId(), scenario, requestedAt);
                    attempt.attachGroup(g.getId(), key);
                    if (g.getPayableAmount() == 0) {
                        attempt.completeWithoutCharge(requestedAt);
                    }
                    attempt = payments.saveAndFlush(attempt);
                    g.transition(OrderStatus.PAYMENT_CONFIRMING);
                    g.setPaymentId(attempt.getId());
                    groups.saveAndFlush(g);
                    for (Order child : children) {
                        if (orders.transitionStatus(
                                        child.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING")
                                != 1) {
                            throw new IllegalStateException("결제 묶음 주문의 상태 변경에 실패했습니다.");
                        }
                    }
                    if (g.getPayableAmount() == 0) {
                        for (Order child : children) {
                            if (child.getSourceCartItemId() != null) {
                                cart.lockOwned(child.getSourceCartItemId(), member);
                            }
                        }
                        for (Order child :
                                children.stream()
                                        .sorted(Comparator.comparing(Order::getSalesInfoId))
                                        .toList()) {
                            guard.lockSales(child.getSalesInfoId());
                        }
                        couponReservations.confirm(member, g.getCouponId());
                        for (Order child : children) {
                            if (orders.transitionStatus(child.getId(), "PAYMENT_CONFIRMING", "PAID")
                                    != 1) {
                                throw new IllegalStateException("결제 금액이 0원인 주문의 상태 변경에 실패했습니다.");
                            }
                            if (stock.consumeReserved(child.getSalesInfoId(), child.getQuantity())
                                    != 1) {
                                throw new IllegalStateException("예약 재고가 누락되었습니다.");
                            }
                            if (child.getSourceCartItemId() != null
                                    && child.getSourceCartItemVersion() != null) {
                                jdbc.update(
                                        "DELETE FROM cart_item WHERE id=? AND member_id=? AND version=?",
                                        child.getSourceCartItemId(),
                                        member,
                                        child.getSourceCartItemVersion());
                            }
                        }
                        g.transition(OrderStatus.PAID);
                        groups.saveAndFlush(g);
                        notificationEvents.publish(
                                "PaymentSucceeded",
                                g.getId(),
                                Map.of(
                                        "paymentId",
                                        attempt.getId(),
                                        "paidAmount",
                                        g.getPayableAmount()));
                        return attempt;
                    }
                    Long id = attempt.getId();
                    AfterCommit.run(() -> engine.schedule(id, scenario));
                    return attempt;
                });
    }

    PaymentAttempt payment(String member, String number, Long id) {
        PaymentGroup g = owned(member, number);
        PaymentAttempt p =
                payments.findById(id)
                        .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "결제를 찾을 수 없습니다."));
        if (!Objects.equals(p.getPaymentGroupId(), g.getId())) {
            throw error(ErrorCode.NOT_FOUND, "결제를 찾을 수 없습니다.");
        }
        return p;
    }

    void cancel(String member, String number) {
        PaymentGroup found = owned(member, number);
        guard.ensure(member);
        tx.executeWithoutResult(
                t -> {
                    guard.lock(member);
                    PaymentGroup g = lockedGroup(found.getId());
                    if (g.getStatus() == OrderStatus.CANCELLED) {
                        return;
                    }
                    if (g.getStatus() != OrderStatus.PENDING_PAYMENT) {
                        throw conflict("결제 전 주문만 취소할 수 있습니다.");
                    }
                    close(g, OrderStatus.CANCELLED);
                });
    }

    boolean expire(Long groupId) {
        PaymentGroup found = groups.findById(groupId).orElseThrow();
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    PaymentGroup g = lockedGroup(groupId);
                    if (g.getStatus() != OrderStatus.PENDING_PAYMENT
                            || g.getExpiresAt().isAfter(Instant.now())) {
                        return false;
                    }
                    close(g, OrderStatus.EXPIRED);
                    return true;
                });
    }

    boolean applyOutcome(Lease lease, PaymentStatus result) {
        if (!result.isConfirmedOutcome()) {
            throw new IllegalArgumentException("확정된 결제 결과가 필요합니다.");
        }
        Long id = lease.id();
        PaymentAttempt attempt = payments.findById(id).orElseThrow();
        if (!attempt.getStatus().isUnconfirmed()) {
            return false;
        }
        PaymentGroup found = groups.findById(attempt.getPaymentGroupId()).orElseThrow();
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    PaymentGroup g = lockedGroup(found.getId());
                    if (g.getStatus() != OrderStatus.PAYMENT_CONFIRMING || !recovery.owns(lease)) {
                        return false;
                    }
                    List<Order> children = orders.findByPaymentGroupIdOrderByIdAsc(g.getId());
                    // 생성 때와 동일하게 장바구니를 재고보다 먼저 잠그고, 재고는 판매 ID 오름차순으로 잠근다.
                    for (Order child : children) {
                        if (child.getSourceCartItemId() != null) {
                            cart.lockOwned(child.getSourceCartItemId(), g.getMemberId());
                        }
                    }
                    for (Order child :
                            children.stream()
                                    .sorted(Comparator.comparing(Order::getSalesInfoId))
                                    .toList()) {
                        guard.lockSales(child.getSalesInfoId());
                    }
                    OrderStatus finalStatus =
                            result == PaymentStatus.SUCCESS ? OrderStatus.PAID : OrderStatus.FAILED;
                    if (result == PaymentStatus.SUCCESS) {
                        couponReservations.confirm(g.getMemberId(), g.getCouponId());
                    } else {
                        couponReservations.release(g.getMemberId(), g.getCouponId());
                    }
                    for (Order child : children) {
                        if (orders.transitionStatus(
                                        child.getId(), "PAYMENT_CONFIRMING", finalStatus.name())
                                != 1) {
                            throw new IllegalStateException("결제 묶음 주문의 상태가 일치하지 않습니다.");
                        }
                        if (result == PaymentStatus.SUCCESS) {
                            if (stock.consumeReserved(child.getSalesInfoId(), child.getQuantity())
                                    != 1) {
                                throw new IllegalStateException("예약 재고가 누락되었습니다.");
                            }
                            if (child.getSourceCartItemId() != null
                                    && child.getSourceCartItemVersion() != null) {
                                jdbc.update(
                                        "DELETE FROM cart_item WHERE id=? AND member_id=? AND version=?",
                                        child.getSourceCartItemId(),
                                        g.getMemberId(),
                                        child.getSourceCartItemVersion());
                            }
                        } else {
                            restore(child);
                        }
                    }
                    g.transition(finalStatus);
                    groups.saveAndFlush(g);
                    if (!recovery.complete(lease, result.name())) {
                        throw new IllegalStateException("결제 결과 반영 중 실행 권한을 잃었습니다.");
                    }
                    notificationEvents.publish(
                            result == PaymentStatus.SUCCESS ? "PaymentSucceeded" : "PaymentFailed",
                            g.getId(),
                            result == PaymentStatus.SUCCESS
                                    ? Map.of("paymentId", id, "paidAmount", g.getPayableAmount())
                                    : Map.of(
                                            "paymentId",
                                            id,
                                            "attemptedAmount",
                                            g.getPayableAmount(),
                                            "failureCode",
                                            result == PaymentStatus.TIMEOUT
                                                    ? "TIMEOUT"
                                                    : "REJECTED"));
                    return true;
                });
    }

    private PaymentGroup lockedGroup(Long id) {
        PaymentGroup g = groups.lockById(id).orElseThrow();
        entityManager.refresh(g);
        return g;
    }

    private void close(PaymentGroup g, OrderStatus terminal) {
        List<Order> children =
                orders.findByPaymentGroupIdOrderByIdAsc(g.getId()).stream()
                        .sorted(Comparator.comparing(Order::getSalesInfoId))
                        .toList();
        for (Order child : children) {
            guard.lockSales(child.getSalesInfoId());
        }
        for (Order child : children) {
            int changed =
                    terminal == OrderStatus.CANCELLED
                            ? orders.cancelOrder(child.getId())
                            : jdbc.update(
                                    "UPDATE orders SET status='EXPIRED',cancelled_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING_PAYMENT'",
                                    child.getId());
            if (changed != 1) {
                throw new IllegalStateException("결제 묶음 종료 상태가 일치하지 않습니다.");
            }
            restore(child);
        }
        couponReservations.release(g.getMemberId(), g.getCouponId());
        g.transition(terminal);
        groups.saveAndFlush(g);
        notificationEvents.publish(
                terminal == OrderStatus.CANCELLED ? "OrderCancelled" : "OrderExpired",
                g.getId(),
                Map.of(
                        terminal == OrderStatus.CANCELLED ? "cancelledAmount" : "expiredAmount",
                        g.getPayableAmount()));
    }

    private void restore(Order child) {
        if (stock.restoreReserved(child.getSalesInfoId(), child.getQuantity()) != 1) {
            throw new IllegalStateException("예약 재고가 누락되었습니다.");
        }
        sales.reopenIfStockAvailable(child.getSalesInfoId());
    }

    private PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "주문 묶음을 찾을 수 없습니다."));
    }

    private static BusinessException conflict(String message) {
        return error(ErrorCode.CONFLICT, message);
    }

    private static BusinessException error(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
