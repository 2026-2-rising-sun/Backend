package com.shoppinglive.commerce.purchase.domain;

import com.shoppinglive.common.persistence.BaseEntity;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="payment_group", uniqueConstraints=@UniqueConstraint(columnNames={"member_id","request_key"}))
public class PaymentGroup extends BaseEntity {
 @Column(name="group_number",nullable=false,unique=true,length=64) private String groupNumber;
 @Column(name="member_id",nullable=false,length=36) private String memberId;
 @Column(name="request_key",nullable=false,length=64) private String requestKey;
 @Column(name="fingerprint",nullable=false,length=64) private String fingerprint;
 @Column(name="total_amount",nullable=false) private Long totalAmount;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private OrderStatus status;
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
 @Column(name="payment_id") private Long paymentId;
 @Version private Long version;
 protected PaymentGroup() {}
 public PaymentGroup(String number,String member,String key,String fingerprint,long total,Instant expires) {
  this.groupNumber=number;this.memberId=member;this.requestKey=key;this.fingerprint=fingerprint;
  this.totalAmount=total;this.expiresAt=expires;this.status=OrderStatus.PENDING_PAYMENT;
 }
 public String getGroupNumber(){return groupNumber;}
 public String getMemberId(){return memberId;}
 public String getRequestKey(){return requestKey;}
 public String getFingerprint(){return fingerprint;}
 public Long getTotalAmount(){return totalAmount;}
 public OrderStatus getStatus(){return status;}
 public Instant getExpiresAt(){return expiresAt;}
 public Long getPaymentId(){return paymentId;}
 public void transition(OrderStatus next){status=next;}
 public void setPaymentId(Long id){paymentId=id;}
}
