package com.shoppinglive.commercecore.payment.relay;

import com.shoppinglive.commercecore.cart.service.CartSelectionPort;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 호출자가 회원 guard와 주문 묶음을 잠근 트랜잭션에서만 결제 결과를 반영한다. */
@Service
public class OrderPaymentResultApplier {
    private final OrderJpaRepository orders;
    private final PaymentGroupRepository groups;
    private final CartSelectionPort cart;
    private final SkuService skus;
    private final CouponReservationService coupons;
    private final PaymentGroupEventPublisher events;
    private final JdbcTemplate jdbc;

    public OrderPaymentResultApplier(
            OrderJpaRepository orders,
            PaymentGroupRepository groups,
            CartSelectionPort cart,
            SkuService skus,
            CouponReservationService coupons,
            PaymentGroupEventPublisher events,
            JdbcTemplate jdbc) {
        this.orders = orders;
        this.groups = groups;
        this.cart = cart;
        this.skus = skus;
        this.coupons = coupons;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(PaymentGroup group, boolean success) {
        finishLocked(group, success, true, success ? OrderStatus.PAID : OrderStatus.FAILED, true);
    }

    /** 보상 대기 전환 트랜잭션에서 재고·쿠폰 예약을 한 번 해제한다. 보상 대기 주문은 되살아나지 않으므로 보상 결과를 기다리지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForCompensation(PaymentGroup group) {
        if (group.getStatus() != OrderStatus.COMPENSATION_PENDING)
            throw new IllegalStateException("보상 대기 주문만 예약을 해제할 수 있습니다.");
        if (jdbc.update(
                        "UPDATE payment_group SET compensation_released_at=CURRENT_TIMESTAMP WHERE id=? AND compensation_released_at IS NULL",
                        group.getId())
                != 1) return;
        for (var item : sorted(orders.findByPaymentGroupIdOrderByIdAsc(group.getId())))
            skus.release(item.getTradeSkuId(), item.getQuantity());
        coupons.release(group.getMemberId(), group.getCouponId());
    }

    /** 시스템 보상 완료는 PG 거절 알림을 합성하지 않고 주문만 종료한다. 예약은 보상 대기 전환 때 이미 해제했다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeCompensation(PaymentGroup group) {
        if (group.getStatus() != OrderStatus.COMPENSATION_PENDING)
            throw new IllegalStateException("시스템 보상을 종료할 진행 주문이 아닙니다.");
        // 해제 표시가 없는 배포 전 보상 대기 주문만 여기서 해제한다.
        releaseForCompensation(group);
        finishLocked(group, false, false, OrderStatus.CANCELLED, false);
    }

    private static List<Order> sorted(List<Order> items) {
        return items.stream().sorted(Comparator.comparing(Order::getTradeSkuId)).toList();
    }

    private void finishLocked(
            PaymentGroup group,
            boolean success,
            boolean notify,
            OrderStatus next,
            boolean release) {
        var items = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
        var purchasedSkus =
                items.stream()
                        .filter(o -> o.getSourceCartItemId() != null)
                        .map(Order::getTradeSkuId)
                        .distinct()
                        .toList();
        if (success && !purchasedSkus.isEmpty())
            cart.lockForCompletion(group.getMemberId(), purchasedSkus);
        for (var item : sorted(items)) {
            if (orders.transitionStatus(item.getId(), group.getStatus().name(), next.name()) != 1)
                throw new IllegalStateException("승인 결과를 적용할 주문 항목 상태가 다릅니다.");
            if (success) {
                skus.consume(item.getTradeSkuId(), item.getQuantity());
            } else if (release) skus.release(item.getTradeSkuId(), item.getQuantity());
        }
        if (success && !purchasedSkus.isEmpty())
            cart.deletePurchased(group.getMemberId(), purchasedSkus);
        if (success)
            coupons.confirmForOrder(group.getMemberId(), group.getCouponId(), group.getId());
        else if (release) coupons.release(group.getMemberId(), group.getCouponId());
        if (next == OrderStatus.FAILED) group.failOrderSheet();
        else group.transition(next);
        groups.saveAndFlush(group);
        if (notify)
            events.publish(
                    success ? "PaymentSucceeded" : "PaymentFailed",
                    group.getId(),
                    success
                            ? Map.of(
                                    "paymentId",
                                    group.getPaymentId(),
                                    "paidAmount",
                                    group.getPayableAmount())
                            : Map.of(
                                    "paymentId",
                                    group.getPaymentId(),
                                    "attemptedAmount",
                                    group.getPayableAmount(),
                                    "failureCode",
                                    "REJECTED"));
    }
}
