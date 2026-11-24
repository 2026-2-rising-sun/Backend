package com.shoppinglive.commercecore.payment.relay;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 팀원 Payment 생성 API와 승인·환불 목표 계약의 Core 측 사본이다. Payment 구현을 참조하지 않는다. */
public final class PaymentRelayContract {
    private PaymentRelayContract() {}

    public record SellerAmount(
            String sellerOrderNumber, String sellerId, long amount, BigDecimal commissionRate) {}

    public record Create(
            String orderRef,
            String buyerId,
            long amount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt,
            List<SellerAmount> sellerAmounts) {}

    public record Approval(String buyerId, String paymentKey, String pgOrderId, long amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Created(
            Long paymentId, String status, String pgOrderId, long amount, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Checkout(String pgOrderId, long amount, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lookup(
            Long paymentId,
            String status,
            long amount,
            long refundedAmount,
            String pgOrderId,
            Instant approvedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApprovalResult(Long paymentId, String status, String code, String message) {}

    public record Reply<T>(
            int httpStatus, boolean success, T data, String errorCode, String location) {}

    public record Approved(long paymentId, String orderRef, long amount, Instant approvedAt) {}

    public record RefundItem(String sellerOrderNumber, long amount) {}

    public record RefundSucceeded(
            long refundId,
            String refundRequestId,
            String reason,
            List<RefundItem> items,
            long amount,
            Instant completedAt) {}

    public record RefundFailed(
            long refundId, String refundRequestId, String reason, String failureCode) {}
}
