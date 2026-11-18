package com.shoppinglive.payment.client.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** 토스 Payment 객체에서 결제 서비스가 쓰는 필드만 읽는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(
        String paymentKey,
        String orderId,
        String status,
        long totalAmount,
        OffsetDateTime approvedAt,
        @JsonProperty("isPartialCancelable") boolean partialCancelable) {}
