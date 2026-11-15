package com.shoppinglive.payment.payment.service;

import static com.shoppinglive.payment.ledger.entity.LedgerAccount.BUYER_DEPOSIT;
import static com.shoppinglive.payment.ledger.entity.LedgerAccount.PG_CUSTODY;

import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.service.Journal;
import com.shoppinglive.payment.ledger.service.LedgerService;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import com.shoppinglive.payment.payment.entity.PgCall;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.entity.PgCallOutcome;
import com.shoppinglive.payment.payment.event.PaymentApproved;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 토스 결과를 결제에 반영한다(승인 ③). 반영마다 한 트랜잭션이고, 상태는 이전 상태 조건부 UPDATE로만 바꾼다. 토스를 부르는 {@link #compensate}만
 * 트랜잭션 밖에서 돈다. 결과 불명 조회(#349)도 {@link #applyDone}·{@link #compensate}를 그대로 쓴다.
 */
@Service
public class PaymentOutcomeService {
    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeService.class);
    static final String COMPENSATION_REASON = "승인 응답 불일치";

    private final PaymentRepository payments;
    private final PgCallRepository pgCalls;
    private final LedgerService ledger;
    private final EventOutbox outbox;
    private final TossPaymentsClient toss;
    private final TransactionTemplate tx;

    public PaymentOutcomeService(
            PaymentRepository payments,
            PgCallRepository pgCalls,
            LedgerService ledger,
            EventOutbox outbox,
            TossPaymentsClient toss,
            TransactionTemplate tx) {
        this.payments = payments;
        this.pgCalls = pgCalls;
        this.ledger = ledger;
        this.outbox = outbox;
        this.toss = toss;
        this.tx = tx;
    }

    /** 토스를 부르기 전에 호출 기록을 UNKNOWN으로 넣어 커밋한다(PAY-CONFIRM-12). */
    @Transactional
    public long insertCall(
            long paymentId, PgCallKind kind, String pgOrderId, String idempotencyKey) {
        return pgCalls.save(new PgCall(paymentId, kind, pgOrderId, idempotencyKey)).getId();
    }

    /**
     * 토스 승인 확인(DONE)을 반영한다. APPROVED·승인 분개·{@code PaymentApproved} outbox를 한 트랜잭션에
     * 쓴다(PAY-CONFIRM-06).
     *
     * @return true면 APPROVED다(이번에 반영했거나 이미 반영됨). false면 토스 승인과 우리 결제가 맞지 않으니 호출자가 트랜잭션 밖에서 {@link
     *     #compensate}를 불러야 한다.
     */
    @Transactional
    public boolean applyDone(long paymentId, long pgCallId, TossPayment response) {
        pgCalls.recordResult(pgCallId, PgCallOutcome.SUCCESS.name(), null);
        Payment payment = payments.findById(paymentId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.APPROVED) {
            // 이미 반영됐다. 승인된 결제는 어떤 응답이 와도 취소하지 않는다
            return true;
        }
        if (!matches(payment, response)) {
            log.error(
                    "토스 승인 응답이 결제와 다르다. 보상 취소한다: paymentId={}, status={}, orderId={}, totalAmount={}",
                    paymentId,
                    response.status(),
                    response.orderId(),
                    response.totalAmount());
            return false;
        }
        Instant approvedAt =
                response.approvedAt() == null ? null : response.approvedAt().toInstant();
        if (payments.approve(paymentId, approvedAt, response.status(), response.partialCancelable())
                == 0) {
            // clearAutomatically로 영속성 컨텍스트를 비웠으므로 다시 읽으면 DB 값이다
            PaymentStatus now = payments.findById(paymentId).orElseThrow().getStatus();
            if (now == PaymentStatus.APPROVED) {
                return true;
            }
            log.error("토스는 승인했는데 결제가 {} 상태다. 보상 취소한다: paymentId={}", now, paymentId);
            return false;
        }
        Payment approved = payments.findById(paymentId).orElseThrow();
        long amount = approved.getAmount();
        ledger.record(
                LedgerRefType.PAYMENT_APPROVED,
                String.valueOf(paymentId),
                List.of(
                        new Journal.Line(PG_CUSTODY, amount, 0, null),
                        new Journal.Line(BUYER_DEPOSIT, 0, amount, null)));
        outbox.append(
                PaymentApproved.TOPIC,
                new PaymentApproved(
                                paymentId, approved.getOrderRef(), amount, approved.getApprovedAt())
                        .envelope(Instant.now()));
        return true;
    }

    /** 첫 호출이 거절이면 CREATED로 되돌린다(PAY-CONFIRM-08). */
    @Transactional
    public void applyDeclined(long paymentId, long pgCallId, String code) {
        pgCalls.recordResult(pgCallId, PgCallOutcome.DECLINED.name(), code);
        revertToCreated(paymentId);
    }

    /** 첫 호출이 토스에 가지 않았으면 CREATED로 되돌린다(PAY-CONFIRM-09). */
    @Transactional
    public void applyNotSent(long paymentId, long pgCallId) {
        pgCalls.recordResult(pgCallId, PgCallOutcome.NOT_SENT.name(), null);
        revertToCreated(paymentId);
    }

    /** 결과를 모르면 UNKNOWN으로 두고 조회로 확정한다(PAY-CONFIRM-10). 마지막 호출 기록에는 그 호출의 실제 결과를 남긴다. */
    @Transactional
    public void applyUnknown(long paymentId, long pgCallId, TossResult last) {
        record(pgCallId, last);
        if (payments.markUnknown(paymentId) == 0) {
            log.warn("APPROVING이 아니어서 UNKNOWN으로 바꾸지 않았다: paymentId={}", paymentId);
        }
    }

    /**
     * 토스에서는 승인됐는데 우리가 승인으로 반영할 수 없는 결제를 전액 취소하고 CANCELED로 둔다(PAY-CONFIRM-07). 트랜잭션 밖에서 부른다.
     *
     * @param attemptPgOrderId 그 승인 시도의 pg_order_id. 결제 행의 값은 이미 비워졌을 수 있어 호출자가 들고 있는 값을 쓴다
     * @param paymentKey 취소할 토스 결제. 토스 응답의 paymentKey다
     */
    public void compensate(long paymentId, String attemptPgOrderId, String paymentKey) {
        String key = "cancel-" + attemptPgOrderId;
        Long callId =
                tx.execute(
                        s ->
                                pgCalls.save(
                                                new PgCall(
                                                        paymentId,
                                                        PgCallKind.CANCEL,
                                                        attemptPgOrderId,
                                                        key))
                                        .getId());
        TossResult result = toss.cancel(paymentKey, COMPENSATION_REASON, key);
        tx.executeWithoutResult(
                s -> {
                    record(Objects.requireNonNull(callId), result);
                    if (payments.markCanceled(paymentId) == 0) {
                        log.error(
                                "보상 취소 뒤 결제를 CANCELED로 바꾸지 못했다(상태 확인 필요): paymentId={}", paymentId);
                    }
                });
        if (!(result instanceof TossResult.Success)) {
            // 감수: CANCELED로 두고 거래 대사가 다시 잡는다
            log.error("보상 취소 결과가 확정되지 않았다: paymentId={}, result={}", paymentId, result);
        }
    }

    private void revertToCreated(long paymentId) {
        if (payments.revertToCreated(paymentId) == 0) {
            log.warn("APPROVING이 아니어서 CREATED로 되돌리지 않았다: paymentId={}", paymentId);
        }
    }

    /** 불명은 기록을 UNKNOWN 그대로 둔다. */
    private void record(long pgCallId, TossResult result) {
        switch (result) {
            case TossResult.Success s ->
                    pgCalls.recordResult(pgCallId, PgCallOutcome.SUCCESS.name(), null);
            case TossResult.Declined d ->
                    pgCalls.recordResult(pgCallId, PgCallOutcome.DECLINED.name(), d.code());
            case TossResult.NotSent n ->
                    pgCalls.recordResult(pgCallId, PgCallOutcome.NOT_SENT.name(), null);
            case TossResult.Unknown u -> {}
        }
    }

    /** PAY-CONFIRM-06 응답 검증. 기대 값은 DB에 저장한 값이다(#349 조회 결과도 같은 기준). */
    private static boolean matches(Payment payment, TossPayment response) {
        return "DONE".equals(response.status())
                && Objects.equals(response.orderId(), payment.getPgOrderId())
                && response.totalAmount() == payment.getAmount()
                && Objects.equals(response.paymentKey(), payment.getPgPaymentKey());
    }
}
