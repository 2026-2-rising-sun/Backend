package com.shoppinglive.payment.refund.entity;

import com.shoppinglive.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 같은 refund_request_id에 다른 본문이 온 수신 기록. 사람이 원인을 본다. 결과는 보내지 않는다. */
@Entity
@Table(name = "refund_request_conflict")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequestConflict extends AuditedEntity {
    public static final String FAILURE_CODE = "REQUEST_CONFLICT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_request_id", nullable = false, updatable = false, length = 64)
    private String refundRequestId;

    @Column(name = "failure_code", nullable = false, updatable = false, length = 32)
    private String failureCode = FAILURE_CODE;

    @Column(
            name = "request_payload",
            nullable = false,
            updatable = false,
            columnDefinition = "text")
    private String requestPayload;

    public RefundRequestConflict(String refundRequestId, String requestPayload) {
        this.refundRequestId = refundRequestId;
        this.requestPayload = requestPayload;
    }
}
