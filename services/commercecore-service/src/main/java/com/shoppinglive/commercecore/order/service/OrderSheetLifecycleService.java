package com.shoppinglive.commercecore.order.service;

import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.payment.service.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 신규 정책은 기존 활성 구매의 상태/기한 처리와 분리한다. */
@Service
public class OrderSheetLifecycleService {
    @org.springframework.beans.factory.annotation.Autowired private RefundIntentStore refundIntents;
    private final KafkaRefundService kafkaRefunds;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PurchaseGuard guard;
    private final SkuService skus;
    private final TransactionTemplate tx;
    private final OrderTimeSource time;
    private final com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events;
    private final com.shoppinglive.commercecore.coupon.service.CouponReservationService
            couponReservations;
    @PersistenceContext private EntityManager entityManager;

    public OrderSheetLifecycleService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PurchaseGuard guard,
            SkuService skus,
            TransactionTemplate tx,
            OrderTimeSource time,
            com.shoppinglive.commercecore.coupon.service.CouponReservationService
                    couponReservations,
            com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher events,
            RefundIntentStore refundIntents,
            KafkaRefundService kafkaRefunds) {
        this.groups = groups;
        this.orders = orders;
        this.guard = guard;
        this.skus = skus;
        this.tx = tx;
        this.time = time;
        this.couponReservations = couponReservations;
        this.events = events;
        this.refundIntents = refundIntents;
        this.kafkaRefunds = kafkaRefunds;
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
                            if (refundIntents.fenced(group.getId())
                                    || !awaitingPayment(group)
                                    || !group.paymentDeadlineReached(time.now())) return false;
                            if (group.getStatus() == OrderStatus.PAYMENT_CONFIRMING)
                                kafkaRefunds.compensateLocked(group, "APPROVAL_UNKNOWN");
                            else expireLocked(group);
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
                    if (refundIntents.fenced(group.getId())
                            || group.getStatus() != OrderStatus.PENDING_PAYMENT)
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

    private boolean awaitingPayment(PaymentGroup group) {
        return group.getStatus() == OrderStatus.PENDING_PAYMENT
                || group.getStatus() == OrderStatus.PAYMENT_CONFIRMING;
    }

    private void expireLocked(PaymentGroup group) {
        releaseItems(group, "EXPIRED");
        group.expireOrderSheet();
        groups.saveAndFlush(group);
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
        String previous = group.getStatus().name();
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
}
