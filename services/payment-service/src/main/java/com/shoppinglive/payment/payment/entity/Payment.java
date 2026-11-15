package com.shoppinglive.payment.payment.entity;

import com.shoppinglive.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 결제 1건. 주문(Commerce)과는 {@code order_ref} 값으로만 연결한다.
 *
 * <p>상태·결제창·승인 값은 setter로 바꾸지 않는다. 이전 상태 조건부 UPDATE로만 바꾼다(0행이면 다시 읽어 판단).
 */
@Entity
@Table(name = "payment")
public class Payment extends AuditedEntity {
    static final String PG_PROVIDER = "TOSS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // unique는 DDL과 같게 둔다. H2 create-drop 테스트의 ON CONFLICT가 이 제약에 기댄다.
    @Column(name = "order_ref", nullable = false, unique = true, updatable = false, length = 64)
    private String orderRef;

    @Column(name = "buyer_id", nullable = false, updatable = false, length = 36)
    private String buyerId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    // DDL DEFAULT는 JPA INSERT가 값을 명시하므로 적용되지 않는다. 필드에서 초기화한다.
    @Column(name = "refunded_amount", nullable = false)
    private Long refundedAmount = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "pg_provider", nullable = false, length = 32)
    private String pgProvider;

    @Column(name = "pg_order_id", unique = true, length = 64)
    private String pgOrderId;

    @Column(name = "checkout_seq", nullable = false)
    private Integer checkoutSeq = 0;

    @Column(name = "pg_payment_key", unique = true, length = 200)
    private String pgPaymentKey;

    @Column(name = "pg_status", length = 32)
    private String pgStatus;

    @Column(name = "partial_cancelable")
    private Boolean partialCancelable;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approving_at")
    private Instant approvingAt;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "check_deadline_at")
    private Instant checkDeadlineAt;

    protected Payment() {
        // JPA 전용
    }

    /** CREATED 결제와 첫 결제창({@code {orderRef}-1})을 만든다. 형식 검증은 API 계층과 DB CHECK가 한다. */
    public Payment(String orderRef, String buyerId, long amount, Instant expiresAt) {
        this.orderRef = orderRef;
        this.buyerId = buyerId;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.status = PaymentStatus.CREATED;
        this.pgProvider = PG_PROVIDER;
        this.checkoutSeq = 1;
        this.pgOrderId = orderRef + "-1";
    }

    public Long getId() {
        return id;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getBuyerId() {
        return buyerId;
    }

    public Long getAmount() {
        return amount;
    }

    public Long getRefundedAmount() {
        return refundedAmount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getPgProvider() {
        return pgProvider;
    }

    public String getPgOrderId() {
        return pgOrderId;
    }

    public Integer getCheckoutSeq() {
        return checkoutSeq;
    }

    public String getPgPaymentKey() {
        return pgPaymentKey;
    }

    public String getPgStatus() {
        return pgStatus;
    }

    public Boolean getPartialCancelable() {
        return partialCancelable;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getApprovingAt() {
        return approvingAt;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public Instant getCheckDeadlineAt() {
        return checkDeadlineAt;
    }
}
