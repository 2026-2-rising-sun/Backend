package com.shoppinglive.payment.payment.service;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.config.PaymentError;
import com.shoppinglive.payment.config.PaymentException;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PgCall;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 토스 결제 승인. ① 검증·APPROVING 전환(트랜잭션) → ② 토스 호출(트랜잭션 밖) → ③ 결과 반영({@link PaymentOutcomeService}, 결과마다
 * 트랜잭션). 클래스에 {@code @Transactional}을 달지 않는다. 토스 호출이 DB 트랜잭션 안에 들어가지 않게 경계를 직접 잡는다.
 */
@Service
public class ApprovalService {
    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final PaymentRepository payments;
    private final PgCallRepository pgCalls;
    private final PaymentOutcomeService outcomes;
    private final TossPaymentsClient toss;
    private final TransactionTemplate tx;

    public ApprovalService(
            PaymentRepository payments,
            PgCallRepository pgCalls,
            PaymentOutcomeService outcomes,
            TossPaymentsClient toss,
            TransactionTemplate tx) {
        this.payments = payments;
        this.pgCalls = pgCalls;
        this.outcomes = outcomes;
        this.toss = toss;
        this.tx = tx;
    }

    /** Commerce가 중계한 승인 요청. 입력 형식은 승인 API가 검사한다. */
    public record ApproveCommand(
            String orderRef, String buyerId, String paymentKey, String pgOrderId, long amount) {}

    /** ①의 결과: 바로 답하거나({@link Answered}) 토스를 부를 시도({@link Attempt}). */
    public sealed interface Start {}

    public record Answered(ApprovalResult result) implements Start {}

    /** APPROVING으로 바꾸고 CONFIRM 호출 기록을 커밋한 시도. 값은 모두 DB 값이고, pgOrderId는 시도 시점 값이다. */
    public record Attempt(
            long paymentId, String paymentKey, String pgOrderId, long amount, long pgCallId)
            implements Start {
        String idempotencyKey() {
            return "confirm-" + pgOrderId;
        }
    }

    /** ①②③을 차례로 한다. 8초 대기는 승인 API가 {@link #start}·{@link #process}를 나눠 불러 처리한다. */
    public ApprovalResult approve(ApproveCommand command) {
        return switch (start(command)) {
            case Answered answered -> answered.result();
            case Attempt attempt -> process(attempt);
        };
    }

    /** ① 검증하고 CREATED→APPROVING. 검사에 걸리면 {@link PaymentException}이고 토스를 부르지 않는다. */
    public Start start(ApproveCommand command) {
        try {
            return tx.execute(s -> begin(command));
        } catch (DataIntegrityViolationException e) {
            // pg_payment_key UNIQUE 위반. 트랜잭션 밖에서 잡으므로 PostgreSQL 트랜잭션 중단과 무관하다
            log.warn(
                    "[보안] 다른 결제가 이미 쓴 paymentKey로 승인 요청: orderRef={}, pgOrderId={}",
                    command.orderRef(),
                    command.pgOrderId());
            throw new PaymentException(PaymentError.INVALID_REQUEST, "paymentKey가 이미 다른 결제에 있습니다.");
        }
    }

    /** ②③ 토스 승인을 부르고 결과를 반영한다. 트랜잭션 밖에서 부른다. */
    public ApprovalResult process(Attempt attempt) {
        long paymentId = attempt.paymentId();
        TossResult first = confirm(attempt);
        TossResult result = first;
        long callId = attempt.pgCallId();
        boolean unknownOnce = first instanceof TossResult.Unknown;
        if (unknownOnce) {
            // 같은 멱등 키로 1번 더(PAY-CONFIRM-10). 호출 기록을 먼저 커밋한다
            callId =
                    outcomes.insertCall(
                            paymentId,
                            PgCallKind.CONFIRM,
                            attempt.pgOrderId(),
                            attempt.idempotencyKey());
            result = confirm(attempt);
        }
        if (result instanceof TossResult.Success success) {
            return done(attempt, callId, success.payment());
        }
        // 한 번이라도 불명이면 첫 요청이 토스에 닿았을 수 있어 CREATED로 되돌리지 않는다
        if (unknownOnce || result instanceof TossResult.Unknown) {
            outcomes.applyUnknown(paymentId, callId, result);
            return new ApprovalResult.Pending(paymentId);
        }
        if (result instanceof TossResult.Declined declined) {
            outcomes.applyDeclined(paymentId, callId, declined.code());
            return new ApprovalResult.Declined(paymentId, declined.code(), declined.message());
        }
        outcomes.applyNotSent(paymentId, callId);
        return new ApprovalResult.PgUnavailable(paymentId);
    }

    private TossResult confirm(Attempt attempt) {
        return toss.confirm(
                attempt.paymentKey(),
                attempt.pgOrderId(),
                attempt.amount(),
                attempt.idempotencyKey());
    }

    private ApprovalResult done(Attempt attempt, long callId, TossPayment response) {
        if (outcomes.applyDone(attempt.paymentId(), callId, response)) {
            return new ApprovalResult.Approved(attempt.paymentId());
        }
        // 응답의 paymentKey를 취소한다. 응답 불일치는 토스가 다른 결제를 승인했다는 뜻일 수 있다
        String target =
                response.paymentKey() != null ? response.paymentKey() : attempt.paymentKey();
        outcomes.compensate(attempt.paymentId(), attempt.pgOrderId(), target);
        return new ApprovalResult.Closed(attempt.paymentId());
    }

    private Start begin(ApproveCommand command) {
        Payment payment =
                payments.findByOrderRef(command.orderRef())
                        .orElseThrow(() -> new PaymentException(PaymentError.PAYMENT_NOT_FOUND));
        Answered answered = check(payment, command);
        if (answered != null) {
            return answered;
        }
        long id = payment.getId();
        if (payments.startApproval(
                        id,
                        command.paymentKey(),
                        command.pgOrderId(),
                        command.amount(),
                        command.buyerId())
                == 0) {
            // 동시 요청에 졌거나 만료됐다. 다시 읽어 같은 순서로 답한다
            Payment current = payments.findById(id).orElseThrow();
            answered = check(current, command);
            if (answered != null) {
                return answered;
            }
            throw new PaymentException(
                    PaymentError.PAYMENT_CLOSED); // 여전히 CREATED면 만료(PAY-CONFIRM-03)
        }
        String key = "confirm-" + command.pgOrderId();
        long callId =
                pgCalls.save(new PgCall(id, PgCallKind.CONFIRM, command.pgOrderId(), key)).getId();
        return new Attempt(
                id, command.paymentKey(), command.pgOrderId(), payment.getAmount(), callId);
    }

    /** 요구사항 순서(01 → 02 → 상태)로 검사한다. CREATED면 null(만료는 조건부 UPDATE가 DB 시각으로 본다). */
    private Answered check(Payment payment, ApproveCommand command) {
        String pgOrderId = command.pgOrderId();
        int dash = pgOrderId.lastIndexOf('-');
        if (dash < 0 || !pgOrderId.substring(0, dash).equals(payment.getOrderRef())) {
            throw new PaymentException(PaymentError.ORDER_ID_MISMATCH);
        }
        if (!payment.getBuyerId().equals(command.buyerId())) {
            log.warn("[보안] 승인 요청 구매자 불일치: orderRef={}", payment.getOrderRef());
            throw new PaymentException(PaymentError.BUYER_MISMATCH);
        }
        if (payment.getAmount() != command.amount()) {
            log.warn(
                    "[보안] 승인 요청 금액 불일치: orderRef={}, amount={}, requested={}",
                    payment.getOrderRef(),
                    payment.getAmount(),
                    command.amount());
            throw new PaymentException(PaymentError.AMOUNT_MISMATCH);
        }
        if (!pgOrderId.equals(payment.getPgOrderId())) {
            throw new PaymentException(PaymentError.STALE_CHECKOUT);
        }
        return switch (payment.getStatus()) {
            case CREATED -> null;
            case APPROVED -> new Answered(new ApprovalResult.Approved(payment.getId()));
            case APPROVING, UNKNOWN -> new Answered(new ApprovalResult.Pending(payment.getId()));
            case CANCELED, PARTIALLY_REFUNDED, REFUNDED ->
                    throw new PaymentException(PaymentError.PAYMENT_CLOSED);
        };
    }
}
