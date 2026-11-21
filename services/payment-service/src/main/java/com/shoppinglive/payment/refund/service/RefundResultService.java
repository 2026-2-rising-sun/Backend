package com.shoppinglive.payment.refund.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundPurpose;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.event.RefundResultRecorded;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행(#370·#386)의 확정 결과를 남긴다. 접수의 실패 결과와 같은 규칙이다: 결과 문자열을 result_payload에 저장하고 같은 트랜잭션 outbox로 보낸다.
 * 같은 요청을 다시 받으면 접수가 이 문자열을 그대로 다시 보낸다.
 *
 * <p>호출자의 트랜잭션에 함께 들어간다. 결제 환불액·분개를 같은 트랜잭션에 쓰려면 호출자가 트랜잭션을 열고 부른다. 토스 호출은 그 밖에서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundResultService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RefundRepository refunds;
    private final EventOutbox outbox;

    /**
     * 확정 결과(SUCCEEDED·FAILED·NO_CHARGE)를 resultVersion을 하나 올려 기록하고 보낸다. 진행 중(PROCESSING·UNKNOWN)에서만
     * 바꾸고, 접수에서 실패하지 않은 시스템 보상만 FAILED 뒤에 SUCCEEDED·NO_CHARGE로 바꿀 수 있다(REF-LATE-02). 판정은 조건부
     * UPDATE가 한다.
     *
     * @param pgTransactionKey 토스 취소 거래 키. 없으면 null(기존 값 유지)
     * @return true면 이번에 기록했다. false면 이미 끝났거나 다른 반영이 먼저 했다(돈은 호출자가 다시 확인)
     * @throws IllegalArgumentException Core가 거부할 결과(성공 금액이 요청과 다름, 고객 환불 NO_CHARGE, 사유 없는 실패 등)
     */
    @Transactional
    public boolean record(
            long refundId,
            RefundStatus status,
            long refundedAmount,
            String failureCode,
            String pgTransactionKey) {
        Refund refund = refunds.findById(refundId).orElseThrow();
        validate(refund, status, refundedAmount, failureCode);
        int version = refund.getResultVersion() + 1;
        Instant resolvedAt = Instant.now();
        String result =
                write(
                        RefundResultRecorded.result(
                                read(refund.getRequestPayload()),
                                refund.getExecutionId(),
                                version,
                                status,
                                refundedAmount,
                                resolvedAt,
                                failureCode));
        if (refunds.recordResult(
                        refundId,
                        refund.getStatus().name(),
                        refund.getResultVersion(),
                        status.name(),
                        version,
                        result,
                        refundedAmount,
                        resolvedAt,
                        failureCode,
                        pgTransactionKey)
                == 0) {
            log.warn(
                    "환불 결과를 바꾸지 않았다(이미 확정됐거나 다른 반영이 먼저 함): refundRequestId={}, status={}, 새 결과={}",
                    refund.getRefundRequestId(),
                    refund.getStatus(),
                    status);
            return false;
        }
        publish(result);
        return true;
    }

    /** #378 Core 결과 소비자가 거부하는 결과를 만들지 않는다. 거부되면 Core 파티션이 막힌다(#389). */
    private static void validate(
            Refund refund, RefundStatus status, long refundedAmount, String failureCode) {
        switch (status) {
            case SUCCEEDED ->
                    require(
                            refundedAmount == refund.getAmount() && failureCode == null,
                            "성공 금액은 요청 금액과 같고 사유는 없어야 한다");
            case FAILED -> requireFailure(refundedAmount, failureCode);
            case NO_CHARGE -> {
                requireFailure(refundedAmount, failureCode);
                require(
                        refund.getPurpose() == RefundPurpose.SYSTEM_COMPENSATION
                                && RefundStatus.NO_CHARGE.name().equals(failureCode),
                        "NO_CHARGE는 시스템 보상에만, 사유 NO_CHARGE로 쓴다");
            }
            default -> throw new IllegalArgumentException("확정 결과만 기록한다: " + status);
        }
    }

    private static void requireFailure(long refundedAmount, String failureCode) {
        require(
                refundedAmount == 0
                        && failureCode != null
                        && !failureCode.isBlank()
                        && failureCode.length() <= 100,
                "실패·미승인은 환불 금액 0과 사유(100자 이하)가 있어야 한다");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void publish(String result) {
        outbox.append(
                RefundResultRecorded.TOPIC,
                RefundResultRecorded.envelope(read(result), Instant.now()));
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
