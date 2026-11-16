package com.shoppinglive.commercecore.payment.dto;

import com.shoppinglive.commercecore.payment.entity.PaymentScenario;
import jakarta.validation.constraints.*;

public record PaymentCommand(
        @NotBlank String requestId,
        @NotNull @Positive Long paymentId,
        @NotNull @Positive Long orderId,
        @Positive Long paymentGroupId,
        @NotBlank @Size(max = 64) String referenceNumber,
        @PositiveOrZero long amount,
        PaymentScenario scenario) {}
