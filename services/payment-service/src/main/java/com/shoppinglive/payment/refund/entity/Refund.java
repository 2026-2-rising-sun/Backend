package com.shoppinglive.payment.refund.entity;

import com.shoppinglive.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Commerce 환불 요청 한 건. 행은 {@code RefundRepository#insertIfAbsent}로만 만들고, 상태·결과는 이전 값 조건부 UPDATE로만
 * 바꾼다.
 *
 * <p>{@code requestPayload}는 받은 요청 payload 문자열 그대로다. 결과에 이 원문을 고치지 않고 실어야 Core가 받아들인다.
 */
@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // unique는 DDL과 같게 둔다. H2 create-drop 테스트의 ON CONFLICT가 이 제약에 기댄다.
    @Column(name = "refund_request_id", nullable = false, unique = true, length = 64)
    private String refundRequestId;

    @Column(name = "execution_id", nullable = false, unique = true)
    private UUID executionId;

    @Column(name = "payment_id")
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private RefundPurpose purpose;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "request_payload", nullable = false, columnDefinition = "text")
    private String requestPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefundStatus status;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "result_version", nullable = false)
    private Integer resultVersion;

    @Column(name = "result_payload", columnDefinition = "text")
    private String resultPayload;
}
