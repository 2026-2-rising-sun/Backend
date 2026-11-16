package com.shoppinglive.commercecore.payment.dto;

import jakarta.validation.constraints.*;

public record RefundCommand(
        @NotBlank String requestId,
        @NotNull @Positive Long coreRefundRequestId,
        @NotNull @Positive Long paymentId,
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long paymentGroupId,
        @NotBlank @Size(max = 64) String referenceNumber,
        @PositiveOrZero long amount) {}
