package com.shoppinglive.payment.ledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 분개 한 줄. 차변·대변 한쪽만 0보다 크다(DB CHECK). 고치거나 지우지 않는다. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_id", nullable = false, updatable = false)
    private Long journalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account", nullable = false, updatable = false, length = 32)
    private LedgerAccount account;

    @Column(name = "debit", nullable = false, updatable = false)
    private Long debit;

    @Column(name = "credit", nullable = false, updatable = false)
    private Long credit;

    @Column(name = "seller_id", updatable = false, length = 36)
    private String sellerId;

    protected LedgerEntry() {
        // JPA 전용
    }

    public LedgerEntry(
            Long journalId, LedgerAccount account, long debit, long credit, String sellerId) {
        this.journalId = journalId;
        this.account = account;
        this.debit = debit;
        this.credit = credit;
        this.sellerId = sellerId;
    }

    public Long getId() {
        return id;
    }

    public Long getJournalId() {
        return journalId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public Long getDebit() {
        return debit;
    }

    public Long getCredit() {
        return credit;
    }

    public String getSellerId() {
        return sellerId;
    }
}
