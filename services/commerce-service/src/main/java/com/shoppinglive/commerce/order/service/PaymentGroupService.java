package com.shoppinglive.commerce.order.service;

import com.shoppinglive.commerce.coupon.service.CouponPreviewService;
import com.shoppinglive.commerce.order.dto.OrderResponse;
import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderStatus;
import com.shoppinglive.commerce.order.entity.PaymentGroup;
import com.shoppinglive.commerce.payment.entity.PaymentAttempt;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import com.shoppinglive.commerce.payment.service.PaymentRecoveryStore.Lease;
import com.shoppinglive.commerce.payment.service.PaymentService;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PaymentGroupService {
    public record Selection(Long itemId, Long version) {}

    public record Item(
            Long itemId,
            Long version,
            Long productId,
            String productName,
            Integer quantity,
            Long unitPrice,
            Long totalAmount,
            Long discountAmount,
            Long payableAmount) {}

    public record Quote(
            List<Item> items,
            Long totalAmount,
            String couponId,
            Long discountAmount,
            Long payableAmount) {}

    public record GroupResponse(
            String groupNumber,
            OrderStatus status,
            Long totalAmount,
            Long discountAmount,
            Long payableAmount,
            String couponId,
            Instant expiresAt,
            List<OrderResponse> orders,
            Long paymentId) {}

    public record Creation(GroupResponse group, boolean created) {}

    private final PaymentGroupCheckoutService checkout;
    private final PaymentGroupLifecycleService lifecycle;
    private final ObjectProvider<PaymentService> paymentService;

    public PaymentGroupService(
            PaymentGroupCheckoutService checkout,
            PaymentGroupLifecycleService lifecycle,
            ObjectProvider<PaymentService> paymentService) {
        this.checkout = checkout;
        this.lifecycle = lifecycle;
        this.paymentService = paymentService;
    }

    public Quote preview(String member, List<Selection> selections) {
        return preview(member, selections, null);
    }

    public Quote preview(String member, List<Selection> selections, String couponId) {
        return checkout.preview(member, selections, couponId);
    }

    public CouponPreviewService.Preview previewSingle(
            String member, String couponId, long productId, long totalAmount) {
        return checkout.previewSingle(member, couponId, productId, totalAmount);
    }

    public Creation create(
            String member,
            List<Selection> selections,
            String buyer,
            String phone,
            long expected,
            String rawKey) {
        return create(member, selections, buyer, phone, expected, null, rawKey);
    }

    public Creation create(
            String member,
            List<Selection> selections,
            String buyer,
            String phone,
            long expected,
            String couponId,
            String rawKey) {
        validateKey(rawKey);
        return checkout.create(member, selections, buyer, phone, expected, couponId, rawKey);
    }

    public PaymentGroup bindSingle(Order order, String requestKey, Long sourceVersion) {
        return bindSingle(order, requestKey, sourceVersion, null, 0L);
    }

    public PaymentGroup bindSingle(
            Order order,
            String requestKey,
            Long sourceVersion,
            String couponId,
            long discountAmount) {
        return checkout.bindSingle(order, requestKey, sourceVersion, couponId, discountAmount);
    }

    public GroupResponse get(String member, String number) {
        return checkout.get(member, number);
    }

    public Optional<GroupResponse> active(String member) {
        return checkout.active(member);
    }

    public PaymentAttempt start(String member, String number, String key) {
        validateKey(key);
        return lifecycle.start(member, number, key);
    }

    public PaymentAttempt payment(String member, String number, Long id) {
        return lifecycle.payment(member, number, id);
    }

    public void cancel(String member, String number) {
        lifecycle.cancel(member, number);
    }

    public boolean expire(Long groupId) {
        return lifecycle.expire(groupId);
    }

    public boolean resolve(Long id) {
        return paymentService.getObject().resolvePayment(id);
    }

    public boolean applyOutcome(Lease lease, PaymentStatus result) {
        return lifecycle.applyOutcome(lease, result);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw error(ErrorCode.INVALID_REQUEST, "멱등키는 1~64자여야 합니다.");
        }
    }

    private static BusinessException error(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }
}
