package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.cart.dto.CartSelection;
import com.shoppinglive.commercecore.cart.service.CartSelectionPort;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.payment.service.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 신규 정책은 기존 활성 구매의 상태/기한 처리와 분리한다. */
@Service
public class OrderSheetLifecycleService {
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PurchaseGuard guard;
    private final SkuService skus;
    private final CartSelectionPort cart;
    private final PaymentDeliveryStore deliveries;
    private final OrderClosureDeliveryService closures;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final OrderTimeSource time;
    private final com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events;
    private final com.shoppinglive.commercecore.coupon.service.CouponPreviewService coupons;
    private final com.shoppinglive.commercecore.coupon.service.CouponReservationService
            couponReservations;
    @PersistenceContext private EntityManager entityManager;

    public OrderSheetLifecycleService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PurchaseGuard guard,
            SkuService skus,
            CartSelectionPort cart,
            PaymentDeliveryStore deliveries,
            OrderClosureDeliveryService closures,
            TransactionTemplate tx,
            JdbcTemplate jdbc,
            OrderTimeSource time,
            com.shoppinglive.commercecore.coupon.service.CouponPreviewService coupons,
            com.shoppinglive.commercecore.coupon.service.CouponReservationService
                    couponReservations,
            com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events) {
        this.groups = groups;
        this.orders = orders;
        this.guard = guard;
        this.skus = skus;
        this.cart = cart;
        this.deliveries = deliveries;
        this.closures = closures;
        this.tx = tx;
        this.jdbc = jdbc;
        this.time = time;
        this.coupons = coupons;
        this.couponReservations = couponReservations;
        this.events = events;
    }

    public PaymentExecution start(String member, String number, String key, Long expectedAmount) {
        return start(member, number, key, expectedAmount, null, null);
    }

    public PaymentExecution start(
            String member,
            String number,
            String key,
            Long expectedAmount,
            String couponId,
            Long couponItemId) {
        if ((couponId == null) != (couponItemId == null))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "쿠폰과 적용 항목을 함께 선택해 주세요.");
        if (key == null || key.isBlank() || key.length() > 64)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "결제 키는 1~64자여야 합니다.");
        PaymentGroup found = owned(member, number);
        guard.ensure(member);
        Start result =
                tx.execute(
                        t -> {
                            guard.lock(member);
                            PaymentGroup group = locked(found.getId());
                            if (awaitingPayment(group)
                                    && !group.getExpiresAt().isAfter(time.now())) {
                                expireLocked(group);
                                return new Start(null, true);
                            }
                            if ("EXPIRED".equals(group.getFailureReason()))
                                return new Start(null, true);
                            if (group.getPaymentId() != null) {
                                if (!Objects.equals(deliveries.key(group.getPaymentId()), key))
                                    throw conflict("이미 다른 키로 결제를 시작했습니다.");
                                if (expectedAmount != null
                                        && (group.getPayableAmount().longValue() != expectedAmount
                                                || !Objects.equals(group.getCouponId(), couponId)
                                                || !Objects.equals(
                                                        group.getCouponOrderItemId(),
                                                        couponItemId)))
                                    throw conflict("기존 결제 요청의 금액 또는 쿠폰 선택과 다릅니다.");
                                return new Start(deliveries.payment(group.getPaymentId()), false);
                            }
                            if (group.getStatus() != OrderStatus.PENDING_PAYMENT)
                                return new Start(null, false);
                            if (!group.getExpiresAt().isAfter(time.now())) {
                                expireLocked(group);
                                return new Start(null, true);
                            }
                            List<Order> selectedItems = items(group);
                            long discount = 0;
                            if (couponId != null) {
                                Order target =
                                        selectedItems.stream()
                                                .filter(
                                                        i ->
                                                                Objects.equals(
                                                                        i.getId(), couponItemId))
                                                .findFirst()
                                                .orElseThrow(() -> conflict("쿠폰 적용 항목이 주문에 없습니다."));
                                discount =
                                        coupons.previewAt(
                                                        member,
                                                        couponId,
                                                        List.of(
                                                                new com.shoppinglive.commercecore
                                                                        .coupon.service
                                                                        .CouponPreviewService
                                                                        .PricedItem(
                                                                        "item:" + target.getId(),
                                                                        target
                                                                                .getProductIdSnapshot(),
                                                                        target.getTotalAmount())),
                                                        time.now())
                                                .discountAmount();
                            }
                            group.selectCoupon(couponId, couponItemId, discount);
                            for (var item : selectedItems)
                                item.selectDiscount(
                                        Objects.equals(item.getId(), couponItemId) ? discount : 0);
                            couponReservations.reserveAt(member, couponId, time.now());
                            if (expectedAmount != null
                                    && group.getPayableAmount().longValue() != expectedAmount)
                                throw conflict("확인한 주문 금액이 다릅니다.");
                            List<Order> items = items(group);
                            var payment =
                                    deliveries.payment(
                                            items.getFirst().getId(),
                                            group.getId(),
                                            group.getGroupNumber(),
                                            group.getPayableAmount(),
                                            key,
                                            null);
                            group.setPaymentId(payment.paymentId());
                            groups.saveAndFlush(group);
                            return new Start(payment, false);
                        });
        if (result.payment() == null)
            throw conflict(result.expired() ? "주문서의 10분 기한이 지났습니다." : "결제를 시작할 수 없는 주문입니다.");
        return result.payment();
    }

    public boolean expire(long id) {
        PaymentGroup found = groups.findById(id).orElseThrow();
        // 생성·결제·재요청 트랜잭션은 guard를 먼저 확보한다. 이미 잠근 채 진입할 때
        // REQUIRES_NEW ensure로 추가 연결을 기다리지 않는다.
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) guard.ensure(found.getMemberId());
        return Boolean.TRUE.equals(
                tx.execute(
                        t -> {
                            guard.lock(found.getMemberId());
                            PaymentGroup group = locked(id);
                            if (!group.enforcesDeadline()
                                    || !awaitingPayment(group)
                                    || group.getExpiresAt().isAfter(time.now())) return false;
                            expireLocked(group);
                            return true;
                        }));
    }

    public void cancel(String member, String number) {
        PaymentGroup found = owned(member, number);
        guard.ensure(member);
        tx.executeWithoutResult(
                t -> {
                    guard.lock(member);
                    PaymentGroup group = locked(found.getId());
                    if (group.getStatus() == OrderStatus.CANCELLED) return;
                    // 결제 결과 대기 중 즉시 취소 제한은 사용자 확정 정책이다.
                    if (group.getStatus() != OrderStatus.PENDING_PAYMENT
                            || group.getPaymentId() != null)
                        throw conflict("결제 요청 전 주문만 즉시 취소할 수 있습니다.");
                    if (!group.getExpiresAt().isAfter(time.now())) {
                        expireLocked(group);
                        return;
                    }
                    releaseItems(group, "CANCELLED");
                    group.transition(OrderStatus.CANCELLED);
                    groups.saveAndFlush(group);
                    events.publish(
                            "OrderCancelled",
                            group.getId(),
                            Map.of("cancelledAmount", group.getPayableAmount()));
                });
    }

    public boolean apply(PaymentExecution result) {
        PaymentGroup found = groups.findById(result.paymentGroupId()).orElseThrow();
        guard.ensure(found.getMemberId());
        return Boolean.TRUE.equals(
                tx.execute(
                        t -> {
                            guard.lock(found.getMemberId());
                            PaymentGroup group = locked(found.getId());
                            var delivery = deliveries.get(result.requestId());
                            if (delivery.terminal()) {
                                deliveries.requireSameResult(delivery, result);
                                return false;
                            }
                            if (!Objects.equals(group.getPaymentId(), result.paymentId()))
                                throw conflict("결제 결과 대상이 다릅니다.");
                            if (awaitingPayment(group) && !group.getExpiresAt().isAfter(time.now()))
                                expireLocked(group);
                            if (group.getStatus() == OrderStatus.FAILED
                                    && "EXPIRED".equals(group.getFailureReason())) {
                                if (result.status() == PaymentStatus.SUCCESS)
                                    closures.compensate(result);
                                deliveries.record(delivery, result, result.status().name());
                                return true;
                            }
                            if (!awaitingPayment(group)) throw conflict("결제 결과 대상 주문의 상태가 다릅니다.");
                            List<Order> items = items(group);
                            boolean success = result.status() == PaymentStatus.SUCCESS;
                            if (success) {
                                var cartIds =
                                        items.stream()
                                                .map(Order::getSourceCartItemId)
                                                .filter(Objects::nonNull)
                                                .distinct()
                                                .toList();
                                if (!cartIds.isEmpty())
                                    cart.lockForCompletion(group.getMemberId(), cartIds);
                                if (group.isOrderSheet())
                                    couponReservations.confirmForOrder(
                                            group.getMemberId(),
                                            group.getCouponId(),
                                            group.getId());
                                else
                                    couponReservations.confirm(
                                            group.getMemberId(), group.getCouponId());
                                for (var item : items) {
                                    transition(group, item, "PAID");
                                    skus.consume(item.getTradeSkuId(), item.getQuantity());
                                }
                                var purchased =
                                        items.stream()
                                                .filter(
                                                        i ->
                                                                i.getSourceCartItemId() != null
                                                                        && i
                                                                                        .getSourceCartItemVersion()
                                                                                != null)
                                                .map(
                                                        i ->
                                                                new CartSelection(
                                                                        i.getSourceCartItemId(),
                                                                        i
                                                                                .getSourceCartItemVersion()))
                                                .toList();
                                if (!purchased.isEmpty())
                                    cart.deletePurchased(group.getMemberId(), purchased);
                                group.transition(OrderStatus.PAID);
                            } else {
                                releaseItems(group, "FAILED");
                                group.failOrderSheet();
                            }
                            groups.saveAndFlush(group);
                            deliveries.record(delivery, result, result.status().name());
                            events.publish(
                                    success ? "PaymentSucceeded" : "PaymentFailed",
                                    group.getId(),
                                    success
                                            ? Map.of(
                                                    "paymentId",
                                                    result.paymentId(),
                                                    "paidAmount",
                                                    group.getPayableAmount())
                                            : Map.of(
                                                    "paymentId",
                                                    result.paymentId(),
                                                    "attemptedAmount",
                                                    group.getPayableAmount(),
                                                    "failureCode",
                                                    "REJECTED"));
                            return true;
                        }));
    }

    private boolean awaitingPayment(PaymentGroup group) {
        return group.getStatus() == OrderStatus.PENDING_PAYMENT
                || (!group.isOrderSheet() && group.getStatus() == OrderStatus.PAYMENT_CONFIRMING);
    }

    private void expireLocked(PaymentGroup group) {
        releaseItems(group, "FAILED");
        group.expireOrderSheet();
        groups.saveAndFlush(group);
        closures.recordExpired(group);
        events.publish(
                "OrderExpired", group.getId(), Map.of("expiredAmount", group.getPayableAmount()));
    }

    private void releaseItems(PaymentGroup group, String status) {
        List<Order> items = items(group);
        couponReservations.release(group.getMemberId(), group.getCouponId());
        // 완료/해제는 신규 거래 검증 포트를 쓰지 않는다. SKU 판매 중지 뒤에도 수량을 반환한다.
        for (var item : items) {
            transition(group, item, status);
            skus.release(item.getTradeSkuId(), item.getQuantity());
        }
    }

    private void transition(PaymentGroup group, Order item, String next) {
        String previous =
                group.isOrderSheet() || group.getPaymentId() == null
                        ? "PENDING_PAYMENT"
                        : "PAYMENT_CONFIRMING";
        if (orders.transitionStatus(item.getId(), previous, next) != 1)
            throw new IllegalStateException("주문항목의 적용 상태가 다릅니다.");
    }

    private List<Order> items(PaymentGroup group) {
        return orders.findByPaymentGroupIdOrderByIdAsc(group.getId()).stream()
                .sorted(Comparator.comparing(Order::getTradeSkuId))
                .toList();
    }

    private PaymentGroup locked(Long id) {
        var group = groups.lockById(id).orElseThrow();
        entityManager.refresh(group);
        return group;
    }

    private PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .filter(PaymentGroup::isOrderSheet)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "주문서를 찾을 수 없습니다."));
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private record Start(PaymentExecution payment, boolean expired) {}
}
