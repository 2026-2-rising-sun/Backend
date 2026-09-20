package com.shoppinglive.commerce.payments.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Mock 결제 시도 엔티티.
 *
 * <p>한 주문에 대해 여러 번 시도될 수 있다 (실패 후 재시도).
 *
 * <p><b>불변 필드 (updatable=false):</b> orderId · scenario · requestedAt.
 * <b>변경 가능:</b> status · resolvedAt · scheduledResolveAt · version.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario", nullable = false, updatable = false, length = 32)
    private PaymentScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "scheduled_resolve_at")
    private Instant scheduledResolveAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PaymentAttempt() {
        // JPA
    }

    /**
     * 결제 시도를 시작한다 (결제 1).
     *
     * <p>초기 상태 = PROCESSING. {@code scheduledResolveAt} 은 시나리오의 delay 를 requestedAt
     * 에 더해 설정. Reconciler (결제 3) 는 이 시각 이후 대상을 스캔.
     */
    public PaymentAttempt(Long orderId, PaymentScenario scenario, Instant requestedAt) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId must not be null");
        }
        if (scenario == null) {
            throw new IllegalArgumentException("scenario must not be null");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt must not be null");
        }
        this.orderId = orderId;
        this.scenario = scenario;
        this.status = PaymentStatus.PROCESSING;
        this.requestedAt = requestedAt;
        this.scheduledResolveAt = requestedAt.plus(scenario.getDelay());
    }

    public Long getOrderId() {
        return orderId;
    }

    public PaymentScenario getScenario() {
        return scenario;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getScheduledResolveAt() {
        return scheduledResolveAt;
    }

    public Long getVersion() {
        return version;
    }
}
