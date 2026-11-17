package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 환불 실행·소비 계약이 연결되기 전에는 환불 필요 사실을 보존한다. REQUIRED는 환불 접수나 완료가 아니다. */
@Repository
public class LateApprovalRefundStore {
    private final JdbcTemplate jdbc;

    public LateApprovalRefundStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void require(PaymentGroup group, PaymentExecution approved) {
        // 호출자는 회원 guard와 주문 묶음 잠금을 잡은 같은 트랜잭션에서 결과도 기록한다.
        if (!jdbc.queryForList(
                        "SELECT payment_id FROM late_approval_refund WHERE payment_id=?",
                        Long.class,
                        approved.paymentId())
                .isEmpty()) return;
        jdbc.update(
                """
                INSERT INTO late_approval_refund(payment_id,payment_group_id,refund_request_id,
                    order_ref,amount,status,approved_at,created_at)
                VALUES (?,?,?,?,?,'REQUIRED',?,CURRENT_TIMESTAMP)
                """,
                approved.paymentId(),
                group.getId(),
                "late-" + approved.paymentId(),
                group.getGroupNumber(),
                approved.amount(),
                Timestamp.from(approved.resolvedAt()));
    }
}
