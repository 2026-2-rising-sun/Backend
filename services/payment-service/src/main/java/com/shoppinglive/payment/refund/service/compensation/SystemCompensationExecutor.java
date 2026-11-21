package com.shoppinglive.payment.refund.service.compensation;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossProperties;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.entity.PgCallOutcome;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository.ConfirmAttempt;
import com.shoppinglive.payment.payment.service.PaymentOutcomeService;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import com.shoppinglive.payment.refund.service.RefundIntakeService;
import com.shoppinglive.payment.refund.service.RefundResultService;
import com.shoppinglive.payment.refund.service.RefundSettlement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시스템 보상 실행(#386). ① 선점 → ② 결제를 막음(트랜잭션) → ③ 토스 조회·취소(트랜잭션 밖) → ④ 결과 기록(트랜잭션). 확정하지 못하면 결과를 보내지 않고
 * PROCESSING으로 두어 다음 차례에 다시 조회한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SystemCompensationExecutor implements CompensationExecutor {
    private static final String NO_CHARGE = RefundStatus.NO_CHARGE.name();
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";
    private static final String ALREADY_CANCELED_PAYMENT = "ALREADY_CANCELED_PAYMENT";
    private static final String NOT_REFUNDABLE = "NOT_REFUNDABLE";
    // 막는 UPDATE와 승인 시작이 번갈아 이겨도 상태는 CANCELED·APPROVED 쪽으로만 끝난다
    private static final int BLOCK_ATTEMPTS = 3;

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PgCallRepository pgCalls;
    private final PaymentOutcomeService outcomes;
    private final RefundResultService results;
    private final RefundSettlement settlement;
    private final TossPaymentsClient toss;
    private final TossProperties tossProperties;
    private final CompensationProperties properties;
    private final TransactionTemplate tx;

    @Override
    public void execute(Refund refund) {
        try {
            Instant now = Instant.now();
            if (refunds.claim(refund.getId(), now, now.plus(confirmWindow())) == 1) {
                run(refunds.findById(refund.getId()).orElseThrow());
            }
        } catch (RuntimeException e) {
            // 선점 시각이 지나면 재처리가 다시 집는다
            log.error("시스템 보상 실행 실패: refundRequestId={}", refund.getRefundRequestId(), e);
        }
    }

    private void run(Refund refund) {
        for (int i = 0; i < BLOCK_ATTEMPTS; i++) {
            Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
            long id = payment.getId();
            if (!refund.getAmount().equals(payment.getAmount())) {
                fail(refund, RefundIntakeService.PAYMENT_MISMATCH);
                return;
            }
            switch (payment.getStatus()) {
                case CREATED -> {
                    // markCanceled는 그 사이 시작된 APPROVING도 막으므로, 조회 없는 NO_CHARGE는 CREATED에서만 한다
                    if (Boolean.TRUE.equals(tx.execute(s -> cancelCreated(refund, id)))) {
                        return;
                    }
                }
                case APPROVING, UNKNOWN -> tx.executeWithoutResult(s -> payments.markCanceled(id));
                case CANCELED -> {
                    if (payment.getPgPaymentKey() == null) {
                        noCharge(refund);
                    } else {
                        resolve(refund, payment);
                    }
                    return;
                }
                case APPROVED -> {
                    refundApproved(refund, payment);
                    return;
                }
                case PARTIALLY_REFUNDED, REFUNDED -> {
                    failAlreadyRefunded(refund, payment);
                    return;
                }
            }
        }
        retryLater(refund);
    }

    private boolean cancelCreated(Refund refund, long paymentId) {
        if (payments.cancelCreated(paymentId) == 0) {
            return false;
        }
        results.record(refund.getId(), RefundStatus.NO_CHARGE, 0, NO_CHARGE, null);
        return true;
    }

    /** 막힌 결제의 토스 상태로 확정한다. 키는 결제창이 비어도 남는 그 시도의 CONFIRM 기록에서 얻는다. */
    private void resolve(Refund refund, Payment payment) {
        ConfirmAttempt attempt = pgCalls.findLatestConfirmAttempt(payment.getId()).orElseThrow();
        Instant asked = Instant.now();
        TossResult result = query(payment, attempt);

        Instant waitUntil = attempt.getFirstRequestedAt().plus(confirmWindow());
        if (result instanceof TossResult.Declined declined
                && NOT_FOUND_PAYMENT.equals(declined.code())) {
            noChargeAfterWait(refund, asked, waitUntil);
        } else if (result instanceof TossResult.Success success) {
            apply(refund, payment, attempt, success.payment(), asked, waitUntil);
        } else {
            retryLater(refund); // 5xx·타임아웃·그 밖의 거절은 돈의 위치를 모른다
        }
    }

    private void apply(
            Refund refund,
            Payment payment,
            ConfirmAttempt attempt,
            TossPayment tossPayment,
            Instant asked,
            Instant waitUntil) {
        switch (tossPayment.status()) {
            case "DONE" -> cancel(refund, payment, attempt);
            case "CANCELED" ->
                    results.record(
                            refund.getId(), RefundStatus.SUCCEEDED, refund.getAmount(), null, null);
            case "ABORTED", "EXPIRED" -> noCharge(refund);
            case "IN_PROGRESS", "READY" -> noChargeAfterWait(refund, asked, waitUntil);
            case "PARTIAL_CANCELED" -> holdForHuman(refund, "토스 결제가 부분 취소 상태다");
            default -> retryLater(refund);
        }
    }

    private void noChargeAfterWait(Refund refund, Instant asked, Instant waitUntil) {
        if (asked.isBefore(waitUntil)) {
            reschedule(refund, waitUntil);
        } else {
            noCharge(refund);
        }
    }

    private void cancel(Refund refund, Payment payment, ConfirmAttempt attempt) {
        TossResult result = requestCancel(payment, attempt);
        if (result instanceof TossResult.Success || isAlreadyCanceled(result)) {
            results.record(refund.getId(), RefundStatus.SUCCEEDED, refund.getAmount(), null, null);
        } else {
            retryLater(refund);
        }
    }

    /**
     * 승인된 결제의 전액 환불. 토스 취소 뒤 트랜잭션 하나로 결제·항목·분개·결과를 반영한다. 내 취소 내역이 없으면 다른 환불이 돌려준 돈이므로 이 요청의 환불로
     * 반영하지 않는다. 그 환불이 반영되면 다음 차례에 NOT_REFUNDABLE로 끝난다.
     */
    private void refundApproved(Refund refund, Payment payment) {
        ConfirmAttempt attempt = pgCalls.findLatestConfirmAttempt(payment.getId()).orElseThrow();
        TossResult result = requestCancel(payment, attempt);
        if (isAlreadyCanceled(result)) {
            result = query(payment, attempt);
        }
        String transactionKey =
                result instanceof TossResult.Success success
                        ? ownCancelKey(success.payment(), refund.getAmount())
                        : null;
        if (transactionKey == null) {
            retryLater(refund);
            return;
        }
        settlement.settle(refund.getId(), transactionKey);
    }

    private static String ownCancelKey(TossPayment payment, long amount) {
        return payment.cancels().stream()
                .filter(
                        c ->
                                "DONE".equals(c.cancelStatus())
                                        && PaymentOutcomeService.COMPENSATION_REASON.equals(
                                                c.cancelReason())
                                        && c.cancelAmount() == amount
                                        && c.transactionKey() != null)
                .map(TossPayment.Cancel::transactionKey)
                .findFirst()
                .orElse(null);
    }

    /** 이 보상의 취소가 토스에 닿았을 수 있으면 환불액 0으로 답하면 안 된다. */
    private void failAlreadyRefunded(Refund refund, Payment payment) {
        ConfirmAttempt attempt = pgCalls.findLatestConfirmAttempt(payment.getId()).orElseThrow();
        if (pgCalls.existsByIdempotencyKeyAndOutcomeIn(
                cancelKey(attempt), List.of(PgCallOutcome.SUCCESS, PgCallOutcome.UNKNOWN))) {
            holdForHuman(refund, "보상 취소가 토스에 닿았을 수 있는데 결제에 다른 환불이 반영됐다");
            return;
        }
        fail(refund, NOT_REFUNDABLE);
    }

    /** 기존 compensate와 키·사유가 같아야 토스가 같은 요청으로 본다. */
    private TossResult requestCancel(Payment payment, ConfirmAttempt attempt) {
        String key = cancelKey(attempt);
        long callId =
                outcomes.insertCall(
                        payment.getId(), PgCallKind.CANCEL, attempt.getPgOrderId(), key);
        TossResult result =
                toss.cancel(
                        payment.getPgPaymentKey(), PaymentOutcomeService.COMPENSATION_REASON, key);
        outcomes.recordCall(callId, result);
        return result;
    }

    private TossResult query(Payment payment, ConfirmAttempt attempt) {
        long callId =
                outcomes.insertCall(
                        payment.getId(), PgCallKind.QUERY, attempt.getPgOrderId(), null);
        TossResult result = toss.getPayment(payment.getPgPaymentKey());
        outcomes.recordCall(callId, result);
        return result;
    }

    private static String cancelKey(ConfirmAttempt attempt) {
        return "cancel-" + attempt.getPgOrderId();
    }

    private static boolean isAlreadyCanceled(TossResult result) {
        return result instanceof TossResult.Declined declined
                && ALREADY_CANCELED_PAYMENT.equals(declined.code());
    }

    private void fail(Refund refund, String failureCode) {
        log.error(
                "[사람 확인 필요] 시스템 보상을 실행할 수 없어 돈을 움직이지 않고 FAILED로 답한다: refundRequestId={}, paymentId={}, failureCode={}",
                refund.getRefundRequestId(),
                refund.getPaymentId(),
                failureCode);
        results.record(refund.getId(), RefundStatus.FAILED, 0, failureCode, null);
    }

    private void noCharge(Refund refund) {
        results.record(refund.getId(), RefundStatus.NO_CHARGE, 0, NO_CHARGE, null);
    }

    private void holdForHuman(Refund refund, String reason) {
        log.error(
                "[사람 확인 필요] 시스템 보상 결과를 보류한다: refundRequestId={}, paymentId={}, reason={}",
                refund.getRefundRequestId(),
                refund.getPaymentId(),
                reason);
        reschedule(refund, null);
    }

    private void retryLater(Refund refund) {
        int attempt = refund.getAttemptCount();
        if (attempt == properties.humanCheckAfter()) {
            log.error(
                    "[사람 확인 필요] 시스템 보상이 {}회 동안 확정되지 않았다. 재시도는 계속한다: refundRequestId={}, paymentId={}",
                    attempt,
                    refund.getRefundRequestId(),
                    refund.getPaymentId());
        }
        reschedule(refund, Instant.now().plus(backoff(attempt)));
    }

    private Duration backoff(int attempt) {
        Duration delay = properties.retryBase().multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(properties.retryMax()) > 0 ? properties.retryMax() : delay;
    }

    private void reschedule(Refund refund, Instant next) {
        refunds.reschedule(refund.getId(), refund.getAttemptCount(), next);
    }

    /** 승인 호출은 불명이면 한 번 더 보내므로 2×응답 한도다. 한 번의 실행(조회+취소) 선점 시간으로도 충분하다. */
    private Duration confirmWindow() {
        return tossProperties
                .connectTimeout()
                .plus(tossProperties.readTimeout())
                .multipliedBy(2)
                .plus(properties.confirmGrace());
    }
}
