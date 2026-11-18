package com.shoppinglive.payment.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** orderRef 형식은 Bean Validation 밖에서 검사한다(400 INVALID_ORDER_REF). */
public record CreatePaymentRequest(
        @NotBlank String orderRef,
        @NotNull UUID buyerId,
        @NotNull @Min(1) Long amount,
        @NotNull OffsetDateTime expiresAt,
        @NotEmpty List<@NotNull @Valid SellerAmountRequest> sellerAmounts) {}
