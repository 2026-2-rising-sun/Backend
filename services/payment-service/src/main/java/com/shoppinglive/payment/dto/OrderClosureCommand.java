package com.shoppinglive.payment.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record OrderClosureCommand(
        @NotBlank String closureRequestId,
        @NotBlank String paymentRequestId,
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long paymentGroupId,
        @NotBlank @Size(max = 64) String referenceNumber,
        @PositiveOrZero long amount,
        @Pattern(regexp = "EXPIRED") @NotNull String reason,
        @NotNull Instant closedAt) {}
