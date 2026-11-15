package com.shoppinglive.payment.payment.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record SellerAmountRequest(
        @NotBlank @Size(max = 64) String sellerOrderNumber,
        @NotNull UUID sellerId,
        @NotNull @Min(1) Long amount,
        @NotNull @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4)
                BigDecimal commissionRate) {}
