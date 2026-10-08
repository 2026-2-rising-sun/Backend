package com.shoppinglive.commerce.purchase.application;
import com.shoppinglive.common.core.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class PurchaseGuard {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate independent;
 public PurchaseGuard(JdbcTemplate jdbc,PlatformTransactionManager manager){
  this.jdbc=jdbc;independent=new TransactionTemplate(manager);
  independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public void ensure(String member){
  try { independent.executeWithoutResult(s->jdbc.update("INSERT INTO member_purchase_guard(member_id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM member_purchase_guard WHERE member_id=?)",member,member)); }
  catch(DataIntegrityViolationException concurrentInsert){
   if(jdbc.queryForObject("SELECT COUNT(*) FROM member_purchase_guard WHERE member_id=?",Integer.class,member)!=1)throw concurrentInsert;
  }
 }
 public void lock(String member){jdbc.queryForObject("SELECT member_id FROM member_purchase_guard WHERE member_id=? FOR UPDATE",String.class,member);}
 public void requireNoActive(String member){
  Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE member_id=? AND status IN ('PENDING_PAYMENT','PAYMENT_CONFIRMING')",Integer.class,member);
  if(active!=null && active>0)throw new BusinessException(ErrorCode.CONFLICT,"진행 중인 주문이 있습니다. 기존 주문을 결제하거나 취소해 주세요.");
 }
 public void lockSales(long id){
  // All stock mutation paths take stock before sales, consistently.
  jdbc.queryForObject("SELECT sales_info_id FROM sales_stock WHERE sales_info_id=? FOR UPDATE",Long.class,id);
  jdbc.queryForObject("SELECT id FROM sales_info WHERE id=? FOR UPDATE",Long.class,id);
 }
}
