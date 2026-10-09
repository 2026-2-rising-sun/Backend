package com.shoppinglive.commerce.purchase.application;
import com.shoppinglive.commerce.payments.domain.*;
import com.shoppinglive.commerce.payments.application.MockPaymentResultUnknownException;
import com.shoppinglive.commerce.payments.application.MockPaymentResultUnknownException.Reason;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
@Service
public class DurableMockGateway {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate independent;
 public DurableMockGateway(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public PaymentStatus authorize(Long attempt,PaymentScenario scenario){
  if(attempt==null || attempt<=0 || scenario==null)throw new IllegalArgumentException("payment attempt and scenario must be valid");
  PaymentStatus existing=find(attempt);
  if(existing!=null)return existing;
  if(scenario.unavailableBeforeResult())throw new MockPaymentResultUnknownException(attempt,Reason.UNAVAILABLE_BEFORE_RESULT);
  boolean created;
  try { created=Boolean.TRUE.equals(independent.execute(s->jdbc.update("INSERT INTO mock_gateway_result(attempt_id,outcome,authorized_at) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM mock_gateway_result WHERE attempt_id=?)",attempt,scenario.getOutcome().name(),Timestamp.from(Instant.now()),attempt)==1)); }
  catch(DataIntegrityViolationException race){if(find(attempt)==null)throw race;created=false;}
  // The independent result commit precedes delivery failure; a retry never replaces it.
  if(created && scenario.losesFirstResponse())throw new MockPaymentResultUnknownException(attempt,Reason.RESPONSE_LOST_AFTER_RESULT);
  return lookup(attempt);
 }
 /** Result lookup never creates an authorization, including when no result exists. */
 public PaymentStatus find(Long attempt){List<String> outcomes=jdbc.queryForList("SELECT outcome FROM mock_gateway_result WHERE attempt_id=?",String.class,attempt);return outcomes.isEmpty()?null:PaymentStatus.valueOf(outcomes.getFirst());}
 public PaymentStatus lookup(Long attempt){PaymentStatus result=find(attempt);if(result==null)throw new IllegalStateException("payment result missing: attemptId="+attempt);return result;}
}
