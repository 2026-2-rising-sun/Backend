package com.shoppinglive.commerce.purchase.domain;
import jakarta.persistence.*;
@Entity @Table(name="member_purchase_guard")
public class MemberPurchaseGuard {
 @Id @Column(name="member_id",length=36) private String memberId;
 protected MemberPurchaseGuard() {}
}
