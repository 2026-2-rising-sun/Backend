package com.shoppinglive.payment.payment.service;

import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PgCall;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 불명(UNKNOWN) 결제를 토스에 조회해 확정한다(PAY-RECOVER-02–04). 결제마다 ① 마감 확인·조회 차례 선점·호출 기록(트랜잭션) → ② 토스
 * 조회(트랜잭션 밖) → ③ 반영({@link PaymentOutcomeService}, 트랜잭션). 클래스에 {@code @Transactional}을 달지 않는다.
 */
@Service
public class UnknownPaymentPoller {
    private static final Logger log = LoggerFactory.getLogger(UnknownPaymentPoller.class);

    private final PaymentRepository payments;
    private final PgCallRepository pgCalls;
    private final PaymentOutcomeService outcomes;
    private final TossPaymentsClient toss;
    private final TransactionTemplate tx;
    private final RecoveryProperties properties;

    public UnknownPaymentPoller(
            PaymentRepository payments,
            PgCallRepository pgCalls,
            PaymentOutcomeService outcomes,
            TossPaymentsClient toss,
            TransactionTemplate tx,
            RecoveryProperties properties) {
        this.payments = payments;
        this.pgCalls = pgCalls;
        this.outcomes = outcomes;
        this.toss = toss;
        this.tx = tx;
        this.properties = properties;
    }

    /** 조회 차례가 된 UNKNOWN 결제를 한 묶음 처리한다. 한 결제의 실패가 나머지를 막지 않는다. */
    public void poll() {
        List<Long> due = tx.execute(s -> payments.findDueChecks(properties.pollBatchSize()));
        for (long id : due) {
            try {
                check(id);
            } catch (RuntimeException e) {
                // 다음 조회 시각은 ①에서 이미 미뤘으므로 같은 결제를 곧바로 다시 집지 않는다
                log.error("결과 불명 조회 실패: paymentId={}", id, e);
            }
        }
    }

    /** ①에서 선점한 조회. 값은 모두 선점 시점의 DB 값이다. */
    private record Claim(String paymentKey, String pgOrderId, long pgCallId) {}

    private void check(long id) {
        Claim claim = tx.execute(s -> claim(id));
        if (claim == null) {
            return;
        }
        TossResult result = toss.getPayment(claim.paymentKey());
        if (result instanceof TossResult.Success success
                && "DONE".equals(success.payment().status())) {
            if (!outcomes.applyDone(id, claim.pgCallId(), success.payment())) {
                // 승인 응답 불일치와 같은 보상 취소(PAY-CONFIRM-07). 응답에 키가 없으면 조회한 키
                String target =
                        success.payment().paymentKey() != null
                                ? success.payment().paymentKey()
                                : claim.paymentKey();
                outcomes.compensate(id, claim.pgOrderId(), target);
            }
            return;
        }
        outcomes.applyQuery(id, claim.pgCallId(), result);
    }

    /**
     * 마감이 지났으면 조회를 멈추고 ERROR 로그(PAY-RECOVER-04). 아니면 다음 조회 시각을 먼저 미뤄 이번 조회를 선점하고 QUERY 호출 기록을 넣는다.
     * 둘 다 조건부 UPDATE라 여러 인스턴스 중 한 곳만 1행이다. null이면 이번에 조회하지 않는다.
     */
    private Claim claim(long id) {
        if (payments.stopChecking(id) == 1) {
            Payment p = payments.findById(id).orElseThrow();
            // paymentKey는 남기지 않는다
            log.error(
                    "payment.recovery.deadline paymentId={} orderRef={} pgOrderId={} lastPgStatus={}",
                    id,
                    p.getOrderRef(),
                    p.getPgOrderId(),
                    p.getPgStatus());
            return null;
        }
        if (payments.claimCheck(id, properties.checkDeadline().toSeconds()) == 0) {
            return null; // UNKNOWN이 아니거나 다른 인스턴스가 먼저 집었다
        }
        Payment p = payments.findById(id).orElseThrow();
        long callId =
                pgCalls.save(new PgCall(id, PgCallKind.QUERY, p.getPgOrderId(), null)).getId();
        return new Claim(p.getPgPaymentKey(), p.getPgOrderId(), callId);
    }
}
