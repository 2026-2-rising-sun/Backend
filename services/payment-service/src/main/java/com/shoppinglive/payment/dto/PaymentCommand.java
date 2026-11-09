package com.shoppinglive.payment.dto;

import com.shoppinglive.payment.entity.PaymentScenario;
import jakarta.validation.constraints.*;

public record PaymentCommand(
        @NotBlank String requestId,
        @NotNull @Positive Long paymentId,
        @NotNull @Positive Long orderId,
        @Positive Long paymentGroupId,
        @NotBlank @Size(max = 64) String referenceNumber,
        @PositiveOrZero long amount,
        PaymentScenario scenario) {}
