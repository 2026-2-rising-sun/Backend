package com.shoppinglive.payment.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * PG 호출 기록. 호출 전에 결과 UNKNOWN으로 먼저 커밋하고, 응답을 받으면 그 행의 결과만 채운다.
 *
 * <p>{@code updated_at}이 없는 테이블이라 {@code AuditedEntity}를 상속하지 않는다.
 */
@Entity
@Table(name = "pg_call")
@EntityListeners(AuditingEntityListener.class)
public class PgCall {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private PgCallKind kind;

    @Column(name = "pg_order_id", updatable = false, length = 64)
    private String pgOrderId;

    @Column(name = "idempotency_key", updatable = false, length = 300)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private PgCallOutcome outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "result_code", length = 64)
    private String resultCode;

    @CreatedDate
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected PgCall() {
        // JPA 전용
    }

    public PgCall(Long paymentId, PgCallKind kind, String pgOrderId, String idempotencyKey) {
        this.paymentId = paymentId;
        this.kind = kind;
        this.pgOrderId = pgOrderId;
        this.idempotencyKey = idempotencyKey;
        this.outcome = PgCallOutcome.UNKNOWN;
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public PgCallKind getKind() {
        return kind;
    }

    public String getPgOrderId() {
        return pgOrderId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public PgCallOutcome getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getResultCode() {
        return resultCode;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }
}
