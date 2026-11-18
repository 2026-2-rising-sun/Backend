package com.shoppinglive.payment.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Commerce가 중계한 승인 요청. paymentKey·pgOrderId·amount는 브라우저가 토스에서 받은 값이다. */
public record ApprovalRequest(
        @NotNull UUID buyerId,
        @NotBlank @Size(max = 200) String paymentKey,
        @NotBlank @Size(max = 64) String pgOrderId,
        @NotNull @Min(1) Long amount) {}
