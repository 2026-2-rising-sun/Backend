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
 @Column(name="discount_amount",nullable=false,updatable=false) private Long discountAmount;
 @Column(name="payable_amount",nullable=false,updatable=false) private Long payableAmount;
 @Column(name="coupon_id",length=36) private String couponId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private OrderStatus status;
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
 @Column(name="payment_id") private Long paymentId;
 @Version private Long version;
 protected PaymentGroup() {}
 public PaymentGroup(String number,String member,String key,String fingerprint,long total,Instant expires) {
  this(number,member,key,fingerprint,total,expires,0L,null);
 }
 public PaymentGroup(String number,String member,String key,String fingerprint,long total,Instant expires,long discount) {
  this(number,member,key,fingerprint,total,expires,discount,null);
 }
 public PaymentGroup(String number,String member,String key,String fingerprint,long total,Instant expires,long discount,String couponId) {
  if(total<=0 || discount<0 || discount>total)throw new IllegalArgumentException("invalid group amounts");
  this.groupNumber=number;this.memberId=member;this.requestKey=key;this.fingerprint=fingerprint;
  this.totalAmount=total;this.discountAmount=discount;this.payableAmount=total-discount;this.couponId=couponId;
  this.expiresAt=expires;this.status=OrderStatus.PENDING_PAYMENT;
 }
 public String getGroupNumber(){return groupNumber;}
 public String getMemberId(){return memberId;}
 public String getRequestKey(){return requestKey;}
 public String getFingerprint(){return fingerprint;}
 public Long getTotalAmount(){return totalAmount;}
 public Long getDiscountAmount(){return discountAmount;}
 public Long getPayableAmount(){return payableAmount;}
 public String getCouponId(){return couponId;}
 public OrderStatus getStatus(){return status;}
 public Instant getExpiresAt(){return expiresAt;}
 public Long getPaymentId(){return paymentId;}
 public void transition(OrderStatus next){status=next;}
 public void setPaymentId(Long id){paymentId=id;}
}
