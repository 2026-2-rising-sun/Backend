package com.shoppinglive.commercecore.payment.entity;

import jakarta.persistence.*;

/** JDBC 원장 중 조회·fence 식별 필드의 매핑. 나머지 원문·결과 컬럼과 제약은 migration이 소유한다. */
@Entity
@Table(name = "core_refund_intent")
class CoreRefundIntent {
    @Id
    @Column(name = "refund_request_id", length = 64)
    private String id;

    @Column(name = "payment_group_id", nullable = false)
    private Long groupId;

    @Column(name = "user_request_id")
    private Long userId;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    protected CoreRefundIntent() {}
}
