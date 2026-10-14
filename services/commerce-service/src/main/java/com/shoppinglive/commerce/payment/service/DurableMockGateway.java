package com.shoppinglive.commerce.payment.service;

import com.shoppinglive.commerce.payment.entity.MockPaymentResultUnknownException;
import com.shoppinglive.commerce.payment.entity.MockPaymentResultUnknownException.Reason;
import com.shoppinglive.commerce.payment.entity.PaymentScenario;
import com.shoppinglive.commerce.payment.entity.PaymentStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DurableMockGateway {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public DurableMockGateway(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public PaymentStatus authorize(Long attempt, PaymentScenario scenario) {
        if (attempt == null || attempt <= 0 || scenario == null) {
            throw new IllegalArgumentException("결제 시도와 시나리오가 올바르지 않습니다.");
        }
        PaymentStatus existing = find(attempt);
        if (existing != null) {
            return existing;
        }
        if (scenario.unavailableBeforeResult()) {
            throw new MockPaymentResultUnknownException(attempt, Reason.UNAVAILABLE_BEFORE_RESULT);
        }
        boolean created;
        try {
            created =
                    Boolean.TRUE.equals(
                            independent.execute(
                                    s ->
                                            jdbc.update(
                                                            "INSERT INTO mock_gateway_result(attempt_id,outcome,authorized_at) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM mock_gateway_result WHERE attempt_id=?)",
                                                            attempt,
                                                            scenario.getOutcome().name(),
                                                            Timestamp.from(Instant.now()),
                                                            attempt)
                                                    == 1));
        } catch (DataIntegrityViolationException race) {
            if (find(attempt) == null) {
                throw race;
            }
            created = false;
        }
        // 독립적인 결과 저장이 먼저 커밋된 뒤 전달 오류가 발생한다. 재시도는 기존 결과를 덮어쓰지 않는다.
        if (created && scenario.losesFirstResponse()) {
            throw new MockPaymentResultUnknownException(attempt, Reason.RESPONSE_LOST_AFTER_RESULT);
        }
        return lookup(attempt);
    }

    /** 결과 조회는 결과가 없을 때도 새 승인을 생성하지 않는다. */
    public PaymentStatus find(Long attempt) {
        List<String> outcomes =
                jdbc.queryForList(
                        "SELECT outcome FROM mock_gateway_result WHERE attempt_id=?",
                        String.class,
                        attempt);
        return outcomes.isEmpty() ? null : PaymentStatus.valueOf(outcomes.getFirst());
    }

    public PaymentStatus lookup(Long attempt) {
        PaymentStatus result = find(attempt);
        if (result == null) {
            throw new IllegalStateException("결제 결과가 누락되었습니다: attemptId=" + attempt);
        }
        return result;
    }
}
