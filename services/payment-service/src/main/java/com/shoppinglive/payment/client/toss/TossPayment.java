package com.shoppinglive.payment.client.toss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/** 토스 Payment 객체에서 결제 서비스가 쓰는 필드만 읽는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(
        String paymentKey,
        String orderId,
        String status,
        long totalAmount,
        OffsetDateTime approvedAt,
        @JsonProperty("isPartialCancelable") boolean partialCancelable,
        List<Cancel> cancels) {

    @JsonCreator
    public TossPayment {
        cancels = cancels == null ? List.of() : List.copyOf(cancels);
    }

    /** 취소 내역이 필요 없는 승인·조회 응답용. */
    public TossPayment(
            String paymentKey,
            String orderId,
            String status,
            long totalAmount,
            OffsetDateTime approvedAt,
            boolean partialCancelable) {
        this(paymentKey, orderId, status, totalAmount, approvedAt, partialCancelable, List.of());
    }

    /** 토스 취소 내역 한 건. {@code cancelStatus}가 DONE이면 취소가 끝났다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancel(
            String transactionKey, String cancelReason, long cancelAmount, String cancelStatus) {}
}
