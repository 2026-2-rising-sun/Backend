package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderPaymentOutcomeService {
    private final OrderSheetLifecycleService sheets;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final CartItemRepository cart;
    private final com.shoppinglive.commercecore.sku.service.SkuService skus;
    private final PaymentDeliveryStore payments;
    private final PurchaseGuard guard;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final CouponReservationService couponReservations;
    private final PaymentGroupEventPublisher notificationEvents;
    @PersistenceContext private EntityManager entityManager;

    public OrderPaymentOutcomeService(
            PaymentGroupRepository groups,
            OrderSheetLifecycleService sheets,
            OrderJpaRepository orders,
            CartItemRepository cart,
            com.shoppinglive.commercecore.sku.service.SkuService skus,
            PaymentDeliveryStore payments,
            PurchaseGuard guard,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            CouponReservationService couponReservations,
            PaymentGroupEventPublisher notificationEvents) {
        this.groups = groups;
        this.sheets = sheets;
        this.orders = orders;
        this.cart = cart;
        this.skus = skus;
        this.payments = payments;
        this.guard = guard;
        this.tx = tx;
        this.jdbc = jdbc;
        this.couponReservations = couponReservations;
        this.notificationEvents = notificationEvents;
    }

    public boolean apply(PaymentExecution result) {
        if (!result.status().isConfirmedOutcome())
            throw new IllegalArgumentException("확정된 결제 결과가 필요합니다.");
        return result.paymentGroupId() != null ? applyGroup(result) : applySingle(result);
    }

    private boolean applyGroup(PaymentExecution result) {
        Long id = result.paymentId();
        PaymentGroup found = groups.findById(result.paymentGroupId()).orElseThrow();
        if (found.isOrderSheet()) return sheets.apply(result);
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    PaymentGroup g = lockedGroup(found.getId());
                    var delivery = payments.get(result.requestId());
                    if (delivery.terminal()) {
                        payments.requireSameResult(delivery, result);
                        return false;
                    }
                    if (g.getStatus() != OrderStatus.PAYMENT_CONFIRMING
                            || !Objects.equals(g.getPaymentId(), id))
                        throw conflict("결제 결과 대상 주문의 상태가 다릅니다.");
                    List<Order> children = orders.findByPaymentGroupIdOrderByIdAsc(g.getId());
                    // 생성 때와 동일하게 장바구니를 재고보다 먼저 잠그고, 재고는 판매 ID 오름차순으로 잠근다.
                    for (Order child : children) {
                        if (child.getSourceCartItemId() != null) {
                            cart.lockOwned(child.getSourceCartItemId(), g.getMemberId());
                        }
                    }
                    for (Order child :
                            children.stream()
                                    .sorted(Comparator.comparing(Order::getTradeSkuId))
                                    .toList()) {
                        // 항목이 SKU ID순으로 정렬되어 수량 명령도 같은 순서로 잠근다.
                    }
                    OrderStatus finalStatus =
                            result.status() == PaymentStatus.SUCCESS
                                    ? OrderStatus.PAID
                                    : OrderStatus.FAILED;
                    if (result.status() == PaymentStatus.SUCCESS) {
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
                        if (result.status() == PaymentStatus.SUCCESS) {
                            skus.consume(child.getTradeSkuId(), child.getQuantity());

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
                    payments.record(delivery, result, result.status().name());
                    notificationEvents.publish(
                            result.status() == PaymentStatus.SUCCESS
                                    ? "PaymentSucceeded"
                                    : "PaymentFailed",
                            g.getId(),
                            result.status() == PaymentStatus.SUCCESS
                                    ? Map.of("paymentId", id, "paidAmount", g.getPayableAmount())
                                    : Map.of(
                                            "paymentId",
                                            id,
                                            "attemptedAmount",
                                            g.getPayableAmount(),
                                            "failureCode",
                                            result.status() == PaymentStatus.TIMEOUT
                                                    ? "TIMEOUT"
                                                    : "REJECTED"));
                    return true;
                });
    }

    private boolean applySingle(PaymentExecution p) {
        return tx.execute(
                t -> {
                    jdbc.queryForObject(
                            "SELECT id FROM orders WHERE id=? FOR UPDATE", Long.class, p.orderId());
                    var current = payments.get(p.requestId());
                    if (current.terminal()) {
                        payments.requireSameResult(current, p);
                        return false;
                    }
                    var order = orders.findById(p.orderId()).orElseThrow();
                    boolean success = p.status() == PaymentStatus.SUCCESS;
                    if (orders.transitionStatus(
                                    order.getId(),
                                    "PAYMENT_CONFIRMING",
                                    success ? "PAID" : "FAILED")
                            != 1) throw new IllegalStateException("단건 결제 주문 상태가 다릅니다.");
                    if (success) {
                        skus.consume(order.getTradeSkuId(), order.getQuantity());
                    } else skus.release(order.getTradeSkuId(), order.getQuantity());
                    payments.record(current, p, p.status().name());
                    return true;
                });
    }

    private PaymentGroup lockedGroup(Long id) {
        PaymentGroup group = groups.lockById(id).orElseThrow();
        entityManager.refresh(group);
        return group;
    }

    private void restore(Order child) {
        skus.release(child.getTradeSkuId(), child.getQuantity());
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
