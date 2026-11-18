package com.shoppinglive.payment.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 분개 머리. 사건 (종류, ID)마다 하나이며 고치거나 지우지 않는다. 정정은 반대 분개로 한다. */
@Entity
// UNIQUE는 DDL과 같게 둔다. H2 create-drop 테스트의 ON CONFLICT가 이 제약에 기댄다.
@Table(
        name = "ledger_journal",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_ledger_journal_ref",
                        columnNames = {"ref_type", "ref_id"}))
@EntityListeners(AuditingEntityListener.class)
public class LedgerJournal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, updatable = false, length = 32)
    private LedgerRefType refType;

    @Column(name = "ref_id", nullable = false, updatable = false, length = 64)
    private String refId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerJournal() {
        // JPA 전용
    }

    public LedgerJournal(LedgerRefType refType, String refId) {
        this.refType = refType;
        this.refId = refId;
    }

    public Long getId() {
        return id;
    }

    public LedgerRefType getRefType() {
        return refType;
    }

    public String getRefId() {
        return refId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
