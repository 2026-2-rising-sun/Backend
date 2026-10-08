package com.shoppinglive.commerce.purchase.application;
import com.shoppinglive.commerce.payments.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
@Service
public class DurableMockGateway {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate independent;
 public DurableMockGateway(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public PaymentStatus authorize(Long attempt,PaymentScenario scenario){
  try { independent.executeWithoutResult(s->jdbc.update("INSERT INTO mock_gateway_result(attempt_id,outcome,authorized_at) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM mock_gateway_result WHERE attempt_id=?)",attempt,scenario.getOutcome().name(),Timestamp.from(Instant.now()),attempt)); }
  catch(DataIntegrityViolationException race){if(jdbc.queryForObject("SELECT COUNT(*) FROM mock_gateway_result WHERE attempt_id=?",Integer.class,attempt)!=1)throw race;}
  return lookup(attempt);
 }
 public PaymentStatus lookup(Long attempt){return PaymentStatus.valueOf(jdbc.queryForObject("SELECT outcome FROM mock_gateway_result WHERE attempt_id=?",String.class,attempt));}
}
