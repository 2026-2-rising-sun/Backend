package com.shoppinglive.payment.refund.service.compensation;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossProperties;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository;
import com.shoppinglive.payment.payment.repository.PgCallRepository.ConfirmAttempt;
import com.shoppinglive.payment.payment.service.PaymentOutcomeService;
import com.shoppinglive.payment.payment.service.RecoveryProperties;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import com.shoppinglive.payment.refund.service.RefundIntakeService;
import com.shoppinglive.payment.refund.service.RefundResultService;
import com.shoppinglive.payment.refund.service.RefundSettlement;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시스템 보상 실행(#386). ① 선점 → ② 결제를 막음(트랜잭션) → ③ 토스 조회·취소(트랜잭션 밖) → ④ 결과 기록(트랜잭션). 확정하지 못하면 결과를 보내지 않고
 * UNKNOWN으로 두어 다음 차례에 조회하고, 취소 내역이 없을 때만 같은 키로 다시 보낸다. 첫 예약부터 24시간이 지나면 멈춘다.
 *
 * <p>모든 상태 변경과 토스 취소는 이 실행이 선점한 {@code attempt_count}를 조건으로 한다. 멈췄다 깨어난 실행은 호출 기록도 결과도 남기지 못한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SystemCompensationExecutor implements CompensationExecutor {
    private static final String NO_CHARGE = RefundStatus.NO_CHARGE.name();
    private static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";
    private static final String ALREADY_CANCELED_PAYMENT = "ALREADY_CANCELED_PAYMENT";
    private static final String NOT_REFUNDABLE = "NOT_REFUNDABLE";
    private static final String NOT_CANCELABLE_PAYMENT = "NOT_CANCELABLE_PAYMENT";
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
    private final RecoveryProperties recovery;
    private final TransactionTemplate tx;

    /** 이 실행. {@code claimed}의 attempt_count가 선점 증표라 다시 읽은 값으로 바꾸지 않는다. */
    private record Run(Refund claimed, Instant deadline) {}

    private record Admitted(Run run, boolean waitForOtherRefund) {}

    @Override
    public void execute(Refund refund) {
        try {
            Instant now = Instant.now();
            Refund due = refunds.findById(refund.getId()).orElseThrow();
            Instant lease = capped(now.plus(confirmWindow()), due.getCheckDeadlineAt(), now);
            if (refunds.claim(refund.getId(), now, lease) == 0) {
                return;
            }
            Refund claimed = refunds.findById(refund.getId()).orElseThrow();
            if (claimed.getAttemptCount() != due.getAttemptCount() + 1) {
                return; // 읽는 사이 다른 실행도 선점했다. 그 값을 내 증표로 쓰면 안 된다
            }
            alertIfOverdue(claimed); // 이번 실행이 예외로 끝나도 알림이 남게 먼저 한다
            runGuarded(claimed);
        } catch (RuntimeException e) {
            log.error("시스템 보상 실행 실패: refundRequestId={}", refund.getRefundRequestId(), e);
        }
    }

    /** 선점 시각이 지나면 재처리가 다시 집는다. 상한이 지났으면 예외가 반복돼도 멈추게 한다. */
    private void runGuarded(Refund claimed) {
        try {
            run(claimed);
        } catch (RuntimeException e) {
            log.error("시스템 보상 실행 실패: refundRequestId={}", claimed.getRefundRequestId(), e);
            Refund current = refunds.findById(claimed.getId()).orElseThrow();
            if (expired(current.getCheckDeadlineAt())) {
                stop(claimed);
            }
        }
    }

    private void run(Refund claimed) {
        for (int i = 0; i < BLOCK_ATTEMPTS; i++) {
            Payment payment = payments.findById(claimed.getPaymentId()).orElseThrow();
            long id = payment.getId();
            if (!claimed.getAmount().equals(payment.getAmount())) {
                tx.executeWithoutResult(
                        s -> {
                            if (holdsRun(claimed)) {
                                fail(claimed, RefundIntakeService.PAYMENT_MISMATCH);
                            }
                        });
                return;
            }
            switch (payment.getStatus()) {
                case CREATED -> {
                    // markCanceled는 그 사이 시작된 APPROVING도 막으므로, 조회 없는 NO_CHARGE는 CREATED에서만 한다
                    if (Boolean.TRUE.equals(tx.execute(s -> cancelCreated(claimed, id)))) {
                        return;
                    }
                }
                case APPROVING, UNKNOWN -> tx.executeWithoutResult(s -> payments.markCanceled(id));
                case CANCELED -> {
                    if (payment.getPgPaymentKey() == null) {
                        finish(claimed, RefundStatus.NO_CHARGE, 0, NO_CHARGE, null);
                    } else {
                        Instant deadline = tx.execute(s -> reserve(claimed));
                        if (deadline != null) {
                            resolve(new Run(claimed, deadline), payment);
                        }
                    }
                    return;
                }
                case APPROVED -> {
                    refundApproved(claimed, payment);
                    return;
                }
                case PARTIALLY_REFUNDED, REFUNDED -> {
                    tx.executeWithoutResult(
                            s -> {
                                payments.findForRefund(id).orElseThrow();
                                if (holdsRun(claimed)) {
                                    failAlreadyRefunded(claimed, payment);
                                }
                            });
                    return;
                }
            }
        }
        retryLater(new Run(claimed, claimed.getCheckDeadlineAt()));
    }

    /**
     * @return true면 끝났다(이 실행이 기록했거나 선점을 잃었다). false면 결제 상태를 다시 읽는다
     */
    private boolean cancelCreated(Refund claimed, long paymentId) {
        payments.findForRefund(paymentId).orElseThrow();
        if (!holdsRun(claimed)) {
            return true;
        }
        if (payments.cancelCreated(paymentId) == 0) {
            return false;
        }
        results.record(claimed.getId(), RefundStatus.NO_CHARGE, 0, NO_CHARGE, null);
        return true;
    }

    /** 이 실행의 선점이 살아 있으면 true이고 트랜잭션 끝까지 환불 행을 잠근다. 트랜잭션 안에서 부른다. */
    private boolean holdsRun(Refund claimed) {
        return refunds.holdProcessing(claimed.getId(), claimed.getAttemptCount()) == 1
                || refunds.holdAttempt(claimed.getId(), claimed.getAttemptCount()) == 1;
    }

    /**
     * 결제 잠금 뒤 선점을 확인하고 UNKNOWN으로 예약한다. 상한은 첫 예약에 한 번만 정한다. 트랜잭션 안에서 부른다.
     *
     * @return 상한 시각. 선점을 잃었으면 null이고 호출 기록도 토스 호출도 하지 않는다
     */
    private Instant reserve(Refund claimed) {
        payments.findForRefund(claimed.getPaymentId()).orElseThrow();
        return holdsRun(claimed) ? reservedDeadline(claimed) : null;
    }

    private Instant reservedDeadline(Refund claimed) {
        refunds.reserve(claimed.getId(), claimed.getAttemptCount(), giveUpAt());
        return refunds.findById(claimed.getId()).orElseThrow().getCheckDeadlineAt();
    }

    /** 막힌 결제의 토스 상태로 확정한다. 키는 결제창이 비어도 남는 그 시도의 CONFIRM 기록에서 얻는다. */
    private void resolve(Run run, Payment payment) {
        ConfirmAttempt attempt = pgCalls.findLatestConfirmAttempt(payment.getId()).orElseThrow();
        Instant asked = Instant.now();
        TossResult result = query(payment, attempt);

        Instant waitUntil = attempt.getFirstRequestedAt().plus(confirmWindow());
        if (result instanceof TossResult.Declined declined
                && NOT_FOUND_PAYMENT.equals(declined.code())) {
            noChargeAfterWait(run, asked, waitUntil);
        } else if (result instanceof TossResult.Success success) {
            apply(run, payment, attempt, success.payment(), asked, waitUntil);
        } else {
            retryLater(run); // 5xx·타임아웃·그 밖의 거절은 돈의 위치를 모른다
        }
    }

    private void apply(
            Run run,
            Payment payment,
            ConfirmAttempt attempt,
            TossPayment tossPayment,
            Instant asked,
            Instant waitUntil) {
        switch (tossPayment.status()) {
            case "DONE" -> cancel(run, payment, attempt);
            case "CANCELED" -> succeed(run.claimed(), tossPayment);
            case "ABORTED", "EXPIRED" ->
                    finish(run.claimed(), RefundStatus.NO_CHARGE, 0, NO_CHARGE, null);
            case "IN_PROGRESS", "READY" -> noChargeAfterWait(run, asked, waitUntil);
            case "PARTIAL_CANCELED" -> holdForHuman(run.claimed(), "토스 결제가 부분 취소 상태다");
            default -> retryLater(run);
        }
    }

    private void noChargeAfterWait(Run run, Instant asked, Instant waitUntil) {
        if (asked.isBefore(waitUntil)) {
            reschedule(run.claimed(), capped(waitUntil, run.deadline(), Instant.now()));
        } else {
            finish(run.claimed(), RefundStatus.NO_CHARGE, 0, NO_CHARGE, null);
        }
    }

    private void cancel(Run run, Payment payment, ConfirmAttempt attempt) {
        if (expired(run.deadline())) {
            retryLater(run); // 멱등 키 만료 전에 멈춘다. 새 취소를 보내지 않는다
            return;
        }
        Refund claimed = run.claimed();
        TossResult result = requestCancel(claimed, payment, attempt);
        if (result == null) {
            return;
        }
        if (isNotCancelable(result)) {
            holdForHuman(claimed, "토스가 취소할 수 없는 결제라고 답했다");
        } else if (result instanceof TossResult.Success success) {
            succeed(claimed, success.payment());
        } else if (isAlreadyCanceled(result)) {
            // 돈은 이미 돌아갔다. 조회가 실패해도 결과를 미루지 않고 거래 키만 비운다
            succeed(
                    claimed,
                    query(payment, attempt) instanceof TossResult.Success success
                            ? success.payment()
                            : null);
        } else {
            retryLater(run);
        }
    }

    /** 승인된 적 없는 결제의 성공. 분개하지 않고 토스 취소 거래 키만 대사용으로 남긴다. */
    private void succeed(Refund claimed, TossPayment canceled) {
        String transactionKey =
                canceled == null
                        ? null
                        : fullCancels(canceled, claimed.getAmount())
                                .map(TossPayment.Cancel::transactionKey)
                                .findFirst()
                                .orElse(null);
        finish(claimed, RefundStatus.SUCCEEDED, claimed.getAmount(), null, transactionKey);
    }

    /**
     * 승인된 결제의 전액 환불. 결제 행 잠금 안에서 아직 환불이 없음을 확인하고 UNKNOWN으로 예약한 뒤 토스 취소하고, 트랜잭션 하나로 결제·항목·분개·결과를
     * 반영한다. 이전 취소가 토스에 닿았을 수 있으면 먼저 조회해 취소 내역이 없을 때만 같은 키로 다시 보낸다. 토스가 이미 취소됐다는데 내 취소 내역이 없으면 우리가
     * 모르는 돈이므로 사람에게 넘긴다.
     */
    private void refundApproved(Refund claimed, Payment payment) {
        Admitted admitted = tx.execute(s -> admit(claimed));
        if (admitted == null) {
            return;
        }
        Run run = admitted.run();
        if (admitted.waitForOtherRefund()) {
            retryLater(run);
            return;
        }
        ConfirmAttempt attempt = pgCalls.findLatestConfirmAttempt(payment.getId()).orElseThrow();
        if (pgCalls.cancelMayHaveLanded(payment.getId(), cancelKey(attempt))) {
            TossResult found = query(payment, attempt);
            if (!(found instanceof TossResult.Success success)) {
                retryLater(run);
                return;
            }
            String landed = ownCancelKey(success.payment(), claimed.getAmount());
            if (landed != null) {
                settle(claimed, landed);
                return;
            }
            if (!"DONE".equals(success.payment().status())) {
                holdForHuman(claimed, "토스 결제가 이 보상이 아닌 다른 취소로 바뀌었다");
                return;
            }
        }
        if (expired(run.deadline())) {
            retryLater(run); // 멱등 키 만료 전에 멈춘다. 새 취소를 보내지 않는다
            return;
        }
        TossResult result = requestCancel(claimed, payment, attempt);
        if (result == null) {
            return;
        }
        if (isNotCancelable(result)) {
            holdForHuman(claimed, "토스가 취소할 수 없는 결제라고 답했다");
            return;
        }
        boolean alreadyCanceled = isAlreadyCanceled(result);
        if (alreadyCanceled) {
            result = query(payment, attempt);
        }
        String transactionKey =
                result instanceof TossResult.Success success
                        ? ownCancelKey(success.payment(), claimed.getAmount())
                        : null;
        if (transactionKey != null) {
            settle(claimed, transactionKey);
        } else if (alreadyCanceled && result instanceof TossResult.Success) {
            holdForHuman(claimed, "토스 결제가 이 보상이 아닌 다른 취소로 끝났다");
        } else {
            retryLater(run);
        }
    }

    /**
     * 고객 환불의 한도 검사와 같은 결제 행 잠금으로 줄을 세운다. 전액 취소는 남은 금액을 모두 돌려주므로 환불이 하나라도 반영됐으면 보내지 않는다. 기다리는 동안에도
     * 상한이 돌도록 먼저 예약한다.
     *
     * @return 선점을 잃었거나 여기서 끝났으면 null
     */
    private Admitted admit(Refund claimed) {
        Payment locked = payments.findForRefund(claimed.getPaymentId()).orElseThrow();
        if (!holdsRun(claimed)) {
            return null;
        }
        if (locked.getStatus() != PaymentStatus.APPROVED || locked.getRefundedAmount() != 0) {
            failAlreadyRefunded(claimed, locked);
            return null;
        }
        Instant deadline = reservedDeadline(claimed);
        // APPROVED에 남은 SUCCEEDED는 0원 환불뿐이라 돈이 겹치지 않는다
        boolean wait =
                refunds.findHoldingLimit(claimed.getPaymentId(), claimed.getId()).stream()
                        .anyMatch(other -> other.getStatus() == RefundStatus.UNKNOWN);
        return new Admitted(new Run(claimed, deadline), wait);
    }

    private void settle(Refund claimed, String transactionKey) {
        tx.executeWithoutResult(
                s -> {
                    if (holdsRun(claimed)) {
                        settlement.settle(claimed.getId(), transactionKey);
                    }
                });
    }

    private static String ownCancelKey(TossPayment payment, long amount) {
        return fullCancels(payment, amount)
                .filter(c -> PaymentOutcomeService.COMPENSATION_REASON.equals(c.cancelReason()))
                .map(TossPayment.Cancel::transactionKey)
                .findFirst()
                .orElse(null);
    }

    private static Stream<TossPayment.Cancel> fullCancels(TossPayment payment, long amount) {
        return payment.cancels().stream()
                .filter(
                        c ->
                                "DONE".equals(c.cancelStatus())
                                        && c.cancelAmount() == amount
                                        && c.transactionKey() != null);
    }

    /** 이 보상의 취소가 토스에 닿았을 수 있으면 환불액 0으로 답하면 안 된다. 선점을 쥔 트랜잭션 안에서 부른다. */
    private void failAlreadyRefunded(Refund claimed, Payment payment) {
        ConfirmAttempt attempt = pgCalls.findLatestConfirmAttempt(payment.getId()).orElseThrow();
        if (pgCalls.cancelMayHaveLanded(payment.getId(), cancelKey(attempt))) {
            holdForHuman(claimed, "보상 취소가 토스에 닿았을 수 있는데 결제에 다른 환불이 반영됐다");
            return;
        }
        fail(claimed, NOT_REFUNDABLE);
    }

    /**
     * 기존 compensate와 키·사유가 같아야 토스가 같은 요청으로 본다. 이 실행의 선점이 살아 있을 때만 호출을 기록한다.
     *
     * @return 다른 실행이 선점했거나 확정됐으면 null
     */
    private TossResult requestCancel(Refund claimed, Payment payment, ConfirmAttempt attempt) {
        String key = cancelKey(attempt);
        Long callId =
                tx.execute(
                        s ->
                                refunds.holdAttempt(claimed.getId(), claimed.getAttemptCount()) == 0
                                        ? null
                                        : outcomes.insertCall(
                                                payment.getId(),
                                                PgCallKind.CANCEL,
                                                attempt.getPgOrderId(),
                                                key));
        if (callId == null) {
            return null;
        }
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

    private static boolean isNotCancelable(TossResult result) {
        return result instanceof TossResult.Declined declined
                && NOT_CANCELABLE_PAYMENT.equals(declined.code());
    }

    /** 돈을 움직이지 않은 호출 전 판정. 선점을 쥔 트랜잭션 안에서 부른다. */
    private void fail(Refund claimed, String failureCode) {
        log.error(
                "[사람 확인 필요] 시스템 보상을 실행할 수 없어 돈을 움직이지 않고 FAILED로 답한다: refundRequestId={}, paymentId={}, failureCode={}",
                claimed.getRefundRequestId(),
                claimed.getPaymentId(),
                failureCode);
        results.record(claimed.getId(), RefundStatus.FAILED, 0, failureCode, null);
    }

    /** 이 실행의 선점이 살아 있을 때만 결과를 쓴다. */
    private void finish(
            Refund claimed,
            RefundStatus status,
            long refundedAmount,
            String failureCode,
            String transactionKey) {
        tx.executeWithoutResult(
                s -> {
                    if (holdsRun(claimed)) {
                        results.record(
                                claimed.getId(),
                                status,
                                refundedAmount,
                                failureCode,
                                transactionKey);
                    }
                });
    }

    private void holdForHuman(Refund claimed, String reason) {
        log.error(
                "[사람 확인 필요] 시스템 보상 결과를 보류한다: refundRequestId={}, paymentId={}, reason={}",
                claimed.getRefundRequestId(),
                claimed.getPaymentId(),
                reason);
        reschedule(claimed, null);
    }

    /** 선점이 시도 횟수를 하나씩 올리므로 같은 횟수는 한 실행만 본다. 재시도는 상한까지 계속한다. */
    private void alertIfOverdue(Refund refund) {
        int attempt = refund.getAttemptCount();
        if (attempt == properties.humanCheckAfter()) {
            log.error(
                    "[사람 확인 필요] 시스템 보상이 {}회 동안 확정되지 않았다. 재시도는 계속한다: refundRequestId={}, paymentId={}",
                    attempt,
                    refund.getRefundRequestId(),
                    refund.getPaymentId());
        }
    }

    private void retryLater(Run run) {
        if (expired(run.deadline())) {
            stop(run.claimed());
            return;
        }
        Instant now = Instant.now();
        reschedule(
                run.claimed(),
                capped(now.plus(backoff(run.claimed().getAttemptCount())), run.deadline(), now));
    }

    private Instant giveUpAt() {
        return Instant.now().plus(recovery.refundGiveUpAfter());
    }

    private static boolean expired(Instant deadline) {
        return deadline != null && !Instant.now().isBefore(deadline);
    }

    /** 다음 시각은 상한을 넘지 않는다. 이미 지난 상한을 넣으면 다른 실행이 곧바로 다시 집으므로 그대로 둔다. */
    private static Instant capped(Instant next, Instant deadline, Instant now) {
        return deadline != null && now.isBefore(deadline) && next.isAfter(deadline)
                ? deadline
                : next;
    }

    /** UNKNOWN으로 한도를 잡은 채 재처리에서 뺀다. 1행일 때만 남겨 인스턴스가 여럿이어도 한 번이다. */
    private void stop(Refund refund) {
        if (refunds.stopRetry(refund.getId(), Instant.now()) == 1) {
            log.error(
                    "payment.refund.deadline [사람 확인 필요] 시스템 보상이 자동 재시도 상한까지 확정되지 않았다:"
                            + " refundRequestId={}, paymentId={}, amount={}",
                    refund.getRefundRequestId(),
                    refund.getPaymentId(),
                    refund.getAmount());
        }
    }

    private Duration backoff(int attempt) {
        Duration delay = properties.retryBase().multipliedBy(1L << Math.min(attempt - 1, 20));
        Duration max = recovery.refundSlowRetryInterval();
        return delay.compareTo(max) > 0 ? max : delay;
    }

    private void reschedule(Refund claimed, Instant next) {
        refunds.reschedule(claimed.getId(), claimed.getAttemptCount(), next);
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
