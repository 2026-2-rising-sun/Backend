package com.shoppinglive.commercecore.payment.relay;

import com.shoppinglive.commercecore.cart.repository.CartItemRepository;
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
    private final CartItemRepository cart;
    private final SkuService skus;
    private final CouponReservationService coupons;
    private final JdbcTemplate jdbc;
    private final PaymentGroupEventPublisher events;

    public OrderPaymentResultApplier(
            OrderJpaRepository orders,
            PaymentGroupRepository groups,
            CartItemRepository cart,
            SkuService skus,
            CouponReservationService coupons,
            JdbcTemplate jdbc,
            PaymentGroupEventPublisher events) {
        this.orders = orders;
        this.groups = groups;
        this.cart = cart;
        this.skus = skus;
        this.coupons = coupons;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void finish(PaymentGroup group, boolean success) {
        var items = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
        if (success)
            items.stream()
                    .filter(o -> o.getSourceCartItemId() != null)
                    .sorted(Comparator.comparing(Order::getSourceCartItemId))
                    .forEach(o -> cart.lockOwned(o.getSourceCartItemId(), group.getMemberId()));
        var next = success ? OrderStatus.PAID : OrderStatus.FAILED;
        for (var item :
                items.stream().sorted(Comparator.comparing(Order::getTradeSkuId)).toList()) {
            if (orders.transitionStatus(item.getId(), "PAYMENT_CONFIRMING", next.name()) != 1)
                throw new IllegalStateException("승인 결과를 적용할 주문 항목 상태가 다릅니다.");
            if (success) {
                skus.consume(item.getTradeSkuId(), item.getQuantity());
                if (item.getSourceCartItemId() != null && item.getSourceCartItemVersion() != null)
                    jdbc.update(
                            "DELETE FROM cart_item WHERE id=? AND member_id=? AND version=?",
                            item.getSourceCartItemId(),
                            group.getMemberId(),
                            item.getSourceCartItemVersion());
            } else skus.release(item.getTradeSkuId(), item.getQuantity());
        }
        if (success)
            coupons.confirmForOrder(group.getMemberId(), group.getCouponId(), group.getId());
        else coupons.release(group.getMemberId(), group.getCouponId());
        if (success) group.transition(next);
        else group.failOrderSheet();
        groups.saveAndFlush(group);
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
