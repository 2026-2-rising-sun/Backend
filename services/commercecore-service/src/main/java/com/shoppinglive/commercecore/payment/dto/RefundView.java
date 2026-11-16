package com.shoppinglive.commercecore.payment.dto;

import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import java.time.Instant;
import java.util.List;

public record RefundView(
        long id,
        String paymentGroupNumber,
        long refundAmount,
        long cumulativeRefundAmount,
        RefundStatus status,
        Instant requestedAt,
        Instant resolvedAt,
        List<RefundTarget> targets,
        RefundRecoveryView recovery) {}
