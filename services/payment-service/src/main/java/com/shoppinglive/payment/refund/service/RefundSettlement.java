package com.shoppinglive.payment.refund.service;

import static com.shoppinglive.payment.ledger.entity.LedgerAccount.BUYER_DEPOSIT;
import static com.shoppinglive.payment.ledger.entity.LedgerAccount.PG_CUSTODY;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.payment.ledger.entity.LedgerRefType;
import com.shoppinglive.payment.ledger.service.Journal;
import com.shoppinglive.payment.ledger.service.LedgerService;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.repository.RefundItemRepository;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 토스 취소가 확인된 환불을 한 트랜잭션으로 반영한다(REF-EXEC-02, LED-REFUND-01). 고객 환불과 시스템 보상이 함께 쓴다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundSettlement {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RefundRepository refunds;
    private final RefundItemRepository items;
    private final PaymentRepository payments;
    private final RefundResultService results;
    private final LedgerService ledger;

    /**
     * 환불 SUCCEEDED·결과 outbox, 결제 환불액·상태, refund_item, 분개를 함께 쓴다. 금액과 항목은 저장된 요청에서 읽는다. 환불 행의 조건부
     * UPDATE가 먼저 성공한 호출만 돈을 반영하므로 동시에 불려도 한 번만 반영된다.
     *
     * @param pgTransactionKey 토스 {@code cancels[].transactionKey}
     * @return true면 이번에 반영했다. false면 이미 확정돼 아무것도 바꾸지 않았다
     * @throws IllegalStateException 결제에 반영할 수 없다. 전체가 롤백되어 환불은 진행 중으로 남고 사람이 확인한다
     */
    @Transactional
    public boolean settle(long refundId, String pgTransactionKey) {
        if (pgTransactionKey == null || pgTransactionKey.isBlank()) {
            throw new IllegalArgumentException("토스 취소 거래 키가 없다");
        }
        Refund refund = refunds.findById(refundId).orElseThrow();
        long paymentId = refund.getPaymentId();
        long amount = refund.getAmount();
        String requestPayload = refund.getRequestPayload();
        String refundRequestId = refund.getRefundRequestId();
        if (!results.record(refundId, RefundStatus.SUCCEEDED, amount, null, pgTransactionKey)) {
            return false;
        }
        if (payments.applyRefund(paymentId, amount) == 0) {
            Payment payment = payments.findById(paymentId).orElseThrow();
            log.error(
                    "[사람 확인 필요] 토스 취소는 끝났는데 결제에 반영할 수 없다: refundRequestId={}, paymentId={}, status={},"
                            + " amount={}, refundedAmount={}, refund={}",
                    refundRequestId,
                    paymentId,
                    payment.getStatus(),
                    payment.getAmount(),
                    payment.getRefundedAmount(),
                    amount);
            throw new IllegalStateException("결제에 환불을 반영할 수 없다: " + refundRequestId);
        }
        sellerAmounts(requestPayload)
                .forEach((seller, value) -> items.insertIfAbsent(refundId, seller, value));
        if (!ledger.record(
                LedgerRefType.REFUND_SUCCEEDED,
                String.valueOf(refundId),
                List.of(
                        new Journal.Line(BUYER_DEPOSIT, amount, 0, null),
                        new Journal.Line(PG_CUSTODY, 0, amount, null)))) {
            throw new IllegalStateException("진행 중 환불에 분개가 이미 있다: " + refundRequestId);
        }
        return true;
    }

    /** 판매자 주문별 금액. refund_item은 0보다 커야 하므로 0원 줄은 뺀다. */
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
