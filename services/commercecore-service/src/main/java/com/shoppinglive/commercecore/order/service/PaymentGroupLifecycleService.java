package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore;
import com.shoppinglive.commercecore.payment.service.PaymentDispatcher;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    private final ObjectProvider<com.shoppinglive.common.observability.BusinessTelemetry> telemetry;
    private final OrderSheetLifecycleService sheets;
    private final OrderTimeSource time;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final com.shoppinglive.commercecore.sku.service.SkuService skus;
    private final PaymentDeliveryStore payments;
    private final PurchaseGuard guard;
    private final PaymentDispatcher dispatcher;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final CouponReservationService couponReservations;
    private final PaymentGroupEventPublisher notificationEvents;
    @PersistenceContext private EntityManager entityManager;

    PaymentGroupLifecycleService(
            PaymentGroupRepository groups,
            OrderSheetLifecycleService sheets,
            OrderTimeSource time,
            OrderJpaRepository orders,
            com.shoppinglive.commercecore.sku.service.SkuService skus,
            PaymentDeliveryStore payments,
            PurchaseGuard guard,
            PaymentDispatcher dispatcher,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            CouponReservationService couponReservations,
            PaymentGroupEventPublisher notificationEvents,
            ObjectProvider<com.shoppinglive.common.observability.BusinessTelemetry> telemetry) {
        this.telemetry = telemetry;
        this.groups = groups;
        this.sheets = sheets;
        this.time = time;
        this.orders = orders;
        this.skus = skus;
        this.payments = payments;
        this.guard = guard;
        this.dispatcher = dispatcher;
        this.tx = tx;
        this.jdbc = jdbc;
        this.couponReservations = couponReservations;
        this.notificationEvents = notificationEvents;
    }

    PaymentExecution start(String member, String number, String key) {
        PaymentGroup found = owned(member, number);
        if (found.isOrderSheet()) {
            var accepted = sheets.start(member, number, key, null);
            return dispatcher.paymentAfterCommit(accepted.paymentId());
        }
        if (found.enforcesDeadline()) sheets.expire(found.getId());
        guard.ensure(member);
        PaymentExecution accepted =
                tx.execute(
                        t -> {
                            guard.lock(member);
                            PaymentGroup g = lockedGroup(found.getId());
                            if (g.enforcesDeadline()
                                    && (sheets.expire(g.getId())
                                            || "EXPIRED".equals(g.getFailureReason()))) return null;
                            if (g.getPaymentId() != null) {
                                if (Objects.equals(payments.key(g.getPaymentId()), key))
                                    return payments.payment(g.getPaymentId());
                                throw conflict("이미 결제를 시작했습니다. 기존 결과를 확인해 주세요.");
                            }
                            if (g.getStatus() != OrderStatus.PENDING_PAYMENT
                                    || !g.getExpiresAt().isAfter(time.now()))
                                throw conflict("결제를 시작할 수 없는 주문입니다.");
                            List<Order> children =
                                    orders.findByPaymentGroupIdOrderByIdAsc(g.getId());
                            PaymentExecution request =
                                    payments.payment(
                                            children.getFirst().getId(),
                                            g.getId(),
                                            number,
                                            g.getPayableAmount(),
                                            key,
                                            null);
                            g.transition(OrderStatus.PAYMENT_CONFIRMING);
                            g.setPaymentId(request.paymentId());
                            groups.saveAndFlush(g);
                            for (Order child : children) {
                                if (orders.transitionStatus(
                                                child.getId(),
                                                "PENDING_PAYMENT",
                                                "PAYMENT_CONFIRMING")
                                        != 1)
                                    throw new IllegalStateException("결제 묶음 주문의 상태 변경에 실패했습니다.");
                            }
                            return request;
                        });
        if (accepted == null) throw conflict("주문의 10분 기한이 지났습니다.");
        // 전달 요청과 주문 상태를 커밋한 뒤 HTTP를 호출한다. 0원도 Payment 원본을 저장한다.
        return dispatcher.paymentAfterCommit(accepted.paymentId());
    }

    PaymentExecution payment(String member, String number, Long id) {
        PaymentGroup g = owned(member, number);
        PaymentExecution p = payments.payment(id);
        if (!Objects.equals(p.paymentGroupId(), g.getId()))
            throw error(ErrorCode.NOT_FOUND, "결제를 찾을 수 없습니다.");
        if (g.enforcesDeadline()) sheets.expire(g.getId());
        return dispatcher.payment(id);
    }

    void cancel(String member, String number) {
        PaymentGroup found = owned(member, number);
        if (found.isOrderSheet()) {
            sheets.cancel(member, number);
            return;
        }
        if (found.enforcesDeadline() && sheets.expire(found.getId())) return;
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
        if (found.enforcesDeadline()) return sheets.expire(groupId);
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    PaymentGroup g = lockedGroup(groupId);
                    if (g.getStatus() != OrderStatus.PENDING_PAYMENT
                            || g.getExpiresAt().isAfter(time.now())) {
                        return false;
                    }
                    close(g, OrderStatus.EXPIRED);
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
                        .sorted(Comparator.comparing(Order::getTradeSkuId))
                        .toList();
        for (Order child : children) {
            // 항목이 SKU ID순으로 정렬되어 수량 명령도 같은 순서로 잠근다.
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
        skus.release(child.getTradeSkuId(), child.getQuantity());
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
