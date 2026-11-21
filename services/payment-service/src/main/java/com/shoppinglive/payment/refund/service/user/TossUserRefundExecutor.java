package com.shoppinglive.payment.refund.service.user;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.client.toss.TossPaymentsClient;
import com.shoppinglive.payment.client.toss.TossResult;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.entity.PaymentSellerAmount;
import com.shoppinglive.payment.payment.entity.PaymentStatus;
import com.shoppinglive.payment.payment.entity.PgCallKind;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.payment.repository.PaymentSellerAmountRepository;
import com.shoppinglive.payment.payment.service.PaymentOutcomeService;
import com.shoppinglive.payment.payment.service.RecoveryProperties;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundPurpose;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import com.shoppinglive.payment.refund.service.RefundResultService;
import com.shoppinglive.payment.refund.service.RefundSettlement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객 환불을 실행한다(REF-ACCEPT-03·04, REF-EXEC-01–04).
 *
 * <p>PROCESSING은 토스에 아직 가지 않은 상태이고, UNKNOWN은 갔을 수 있는 상태다. 결제 행 잠금 안에서 한도를 검사하고 UNKNOWN으로 예약해 커밋한 뒤
 * 트랜잭션 밖에서 토스를 부른다. 그래서 UNKNOWN은 FAILED로 단정하지 않고 조회로 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TossUserRefundExecutor implements UserRefundExecutor {
    static final String OVER_REFUND = "OVER_REFUND";
    static final String PARTIAL_NOT_ALLOWED = "PARTIAL_NOT_ALLOWED";
    static final String UNKNOWN_SELLER_ORDER = "UNKNOWN_SELLER_ORDER";
    static final String NOT_REFUNDABLE = "NOT_REFUNDABLE";
    // 토스 호출 최대 63초 × 2(조회 + 재요청)보다 길게
    private static final Duration LEASE = Duration.ofMinutes(3);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentSellerAmountRepository sellerAmounts;
    private final RefundResultService results;
    private final RefundSettlement settlement;
    private final PaymentOutcomeService calls;
    private final TossPaymentsClient toss;
    private final TransactionTemplate tx;
    private final RecoveryProperties recovery;

    @Override
    public void execute(Refund refund) {
        run(refund.getId());
    }

    @Override
    public void retryDue() {
        Instant now = Instant.now();
        for (Refund refund :
                refunds.findDue(RefundPurpose.USER_REFUND.name(), now, recovery.pollBatchSize())) {
            run(refund.getId());
        }
    }

    /** 예외를 밖으로 내지 않는다. 선점 시각이 지나면 재처리가 다시 집는다. */
    private void run(long id) {
        try {
            Instant now = Instant.now();
            if (refunds.claim(id, now, now.plus(LEASE)) == 0) {
                return;
            }
            Refund refund = refunds.findById(id).orElseThrow();
            if (refund.getStatus() == RefundStatus.PROCESSING) {
                first(refund);
            } else if (refund.getStatus() == RefundStatus.UNKNOWN) {
                recover(refund);
            }
        } catch (RuntimeException e) {
            log.error("고객 환불 실행 실패. 재처리가 다시 집는다: refundId={}", id, e);
        }
    }

    private record CancelCall(String paymentKey, Long cancelAmount, long callId) {}

    private void first(Refund refund) {
        CancelCall call = tx.execute(s -> admit(refund.getId()));
        if (call == null) {
            return;
        }
        TossResult result =
                toss.cancel(
                        call.paymentKey(),
                        reason(refund),
                        refund.getRefundRequestId(),
                        call.cancelAmount());
        calls.recordCall(call.callId(), result);
        switch (result) {
            case TossResult.Success success -> confirm(refund, success.payment());
            case TossResult.Declined declined ->
                    results.record(refund.getId(), RefundStatus.FAILED, 0, declined.code(), null);
            case TossResult.NotSent notSent -> refunds.releaseUnknown(refund.getId());
            case TossResult.Unknown unknown ->
                    log.warn(
                            "환불 취소 결과를 모른다: refundRequestId={}, reason={}",
                            refund.getRefundRequestId(),
                            unknown.reason());
        }
    }

    /** 결제 행 잠금 안에서 검사하고 UNKNOWN으로 예약한다. 검사에 걸리면 FAILED 결과를 쓰고 null이다. */
    private CancelCall admit(long id) {
        Refund refund = refunds.findById(id).orElseThrow();
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return null;
        }
        Payment payment = payments.findForRefund(refund.getPaymentId()).orElseThrow();
        String failure = validate(refund, payment);
        if (failure != null) {
            log.warn(
                    "환불 검증 실패로 돈을 움직이지 않는다: refundRequestId={}, failureCode={}",
                    refund.getRefundRequestId(),
                    failure);
            results.record(id, RefundStatus.FAILED, 0, failure, null);
            return null;
        }
        if (refund.getAmount() == 0) {
            // 쿠폰 등으로 실결제액이 0원인 항목. Core가 주문·재고·쿠폰을 복원하도록 성공으로 답한다(REF-EXEC-05)
            results.record(id, RefundStatus.SUCCEEDED, 0, null, null);
            return null;
        }
        // 금액은 둘 다 바뀌지 않는 값이라 재요청 본문도 같다. 전액은 금액을 보내지 않아야 부분 취소 불가 결제도 취소된다
        Long cancelAmount =
                refund.getAmount().equals(payment.getAmount()) ? null : refund.getAmount();
        long callId =
                calls.insertCall(
                        payment.getId(),
                        PgCallKind.CANCEL,
                        payment.getPgOrderId(),
                        refund.getRefundRequestId());
        CancelCall call = new CancelCall(payment.getPgPaymentKey(), cancelAmount, callId);
        return refunds.markUnknown(id, Instant.now().plus(recovery.checkDeadline())) == 1
                ? call
                : null;
    }

    private String validate(Refund refund, Payment payment) {
        long amount = refund.getAmount();
        if (payment.getPgPaymentKey() == null
                || (payment.getStatus() != PaymentStatus.APPROVED
                        && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED)) {
            return NOT_REFUNDABLE;
        }
        Map<String, Long> requested = sellerAmounts(refund.getRequestPayload());
        Map<String, Long> limits =
                sellerAmounts.findByPaymentId(payment.getId()).stream()
                        .collect(
                                Collectors.toMap(
                                        PaymentSellerAmount::getSellerOrderNumber,
                                        PaymentSellerAmount::getAmount));
        if (!limits.keySet().containsAll(requested.keySet())) {
            return UNKNOWN_SELLER_ORDER;
        }
        // settle 뒤에 실패하면 돈만 돌아가고 반영이 막히므로 항목 합을 토스 호출 전에 맞춘다
        if (requested.values().stream().mapToLong(Long::longValue).sum() != amount) {
            return NOT_REFUNDABLE;
        }
        if (amount == 0) {
            return null; // 돌려줄 돈이 없어 한도를 쓰지 않는다
        }
        if (amount < payment.getAmount() && !Boolean.TRUE.equals(payment.getPartialCancelable())) {
            return PARTIAL_NOT_ALLOWED;
        }
        long used = payment.getRefundedAmount();
        Map<String, Long> sellerUsed = new HashMap<>();
        for (Refund other : refunds.findHoldingLimit(payment.getId(), refund.getId())) {
            if (other.getStatus() != RefundStatus.SUCCEEDED) {
                used += other.getAmount();
            }
            sellerAmounts(other.getRequestPayload())
                    .forEach((seller, value) -> sellerUsed.merge(seller, value, Long::sum));
        }
        if (amount > payment.getAmount() - used) {
            return OVER_REFUND;
        }
        for (Map.Entry<String, Long> item : requested.entrySet()) {
            long limit = limits.get(item.getKey()) - sellerUsed.getOrDefault(item.getKey(), 0L);
            if (item.getValue() > limit) {
                return OVER_REFUND;
            }
        }
        return null;
    }

    /** 토스에 갔을 수 있는 환불. 취소 내역이 있으면 반영하고, 없으면 마감 전까지 같은 멱등 키·본문으로 다시 요청한다. */
    private void recover(Refund refund) {
        Payment payment = payments.findById(refund.getPaymentId()).orElseThrow();
        long queryId =
                calls.insertCall(payment.getId(), PgCallKind.QUERY, payment.getPgOrderId(), null);
        TossResult query = toss.getPayment(payment.getPgPaymentKey());
        calls.recordCall(queryId, query);
        if (query instanceof TossResult.Success found
                && transactionKey(refund, found.payment()) != null) {
            confirm(refund, found.payment());
            return;
        }
        Instant now = Instant.now();
        if (!now.isBefore(refund.getCheckDeadlineAt())) {
            if (refunds.stopRetry(refund.getId(), now) == 1) {
                log.error(
                        "payment.refund.deadline [사람 확인 필요] 조회 마감까지 환불 결과를 확정하지 못했다:"
                                + " refundRequestId={}, orderRef={}, amount={}",
                        refund.getRefundRequestId(),
                        payment.getOrderRef(),
                        refund.getAmount());
            }
            return;
        }
        if (!(query instanceof TossResult.Success)) {
            return;
        }
        long callId =
                calls.insertCall(
                        payment.getId(),
                        PgCallKind.CANCEL,
                        payment.getPgOrderId(),
                        refund.getRefundRequestId());
        Long cancelAmount =
                refund.getAmount().equals(payment.getAmount()) ? null : refund.getAmount();
        TossResult result =
                toss.cancel(
                        payment.getPgPaymentKey(),
                        reason(refund),
                        refund.getRefundRequestId(),
                        cancelAmount);
        calls.recordCall(callId, result);
        if (result instanceof TossResult.Success success) {
            confirm(refund, success.payment());
        } else {
            // 거절이어도 앞선 호출이 성공했을 수 있어 FAILED로 단정하지 않는다
            log.warn(
                    "환불 재요청이 확정되지 않아 UNKNOWN을 유지한다: refundRequestId={}, result={}",
                    refund.getRefundRequestId(),
                    result.getClass().getSimpleName());
        }
    }

    private void confirm(Refund refund, TossPayment payment) {
        String key = transactionKey(refund, payment);
        if (key == null) {
            log.error(
                    "[사람 확인 필요] 토스 응답에서 이 환불의 취소 내역을 찾지 못해 UNKNOWN으로 둔다: refundRequestId={}",
                    refund.getRefundRequestId());
            return;
        }
        settlement.settle(refund.getId(), key);
    }

    /** 토스 취소 내역에는 멱등 키가 없어, 요청마다 고유한 취소 사유와 금액으로 이 환불의 취소를 찾는다. */
    private static String transactionKey(Refund refund, TossPayment payment) {
        List<TossPayment.Cancel> mine =
                payment.cancels().stream()
                        .filter(c -> reason(refund).equals(c.cancelReason()))
                        .filter(c -> "DONE".equals(c.cancelStatus()))
                        .filter(c -> c.cancelAmount() == refund.getAmount())
                        .filter(c -> c.transactionKey() != null && !c.transactionKey().isBlank())
                        .toList();
        return mine.size() == 1 ? mine.get(0).transactionKey() : null;
    }

    private static String reason(Refund refund) {
        return "고객 환불 " + refund.getRefundRequestId();
    }

    /** 판매자 주문별 금액. 0원 줄은 결제의 판매자 금액에 없을 수 있어 뺀다(REF-EXEC-05). */
    private static Map<String, Long> sellerAmounts(String requestPayload) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        try {
            for (JsonNode item : JSON.readTree(requestPayload).path("items")) {
                long value = item.path("amount").longValue();
                if (value > 0) {
                    amounts.merge(item.path("sellerOrderNumber").asText(), value, Long::sum);
                }
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return amounts;
    }
}
