package com.shoppinglive.payment.refund.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 환불 확정 결과 이벤트. 계약 원본은 {@code contracts/events/payment-refund-results.yaml}.
 *
 * <p>payload는 받은 요청 payload에 Payment 필드 7개를 더한 것이다. Core는 요청 필드를 원문과 JSON 값으로 비교하고, 맞지 않으면 결과를 무한
 * 재시도해 파티션이 막힌다(#389). 그래서 요청 필드를 다시 만들지 않고 원문 노드를 복사한다.
 */
public final class RefundResultRecorded {
    public static final String TOPIC = "payment.refund.results.v1";
    public static final String EVENT_TYPE = "RefundResultRecorded";

    private RefundResultRecorded() {}

    /** 돈을 움직이지 않은 첫 결과(resultVersion 1). */
    public static ObjectNode failed(
            JsonNode request, UUID executionId, String failureCode, Instant resolvedAt) {
        return result(request, executionId, 1, RefundStatus.FAILED, 0, resolvedAt, failureCode);
    }

    /** 요청 원문 노드를 복사해 Payment 필드 7개를 더한다. nextActionAt은 확정 결과라 항상 null이다. */
    public static ObjectNode result(
            JsonNode request,
            UUID executionId,
            int resultVersion,
            RefundStatus status,
            long refundedAmount,
            Instant resolvedAt,
            String failureCode) {
        ObjectNode result = request.deepCopy();
        result.put("executionId", executionId.toString());
        result.put("resultVersion", resultVersion);
        result.put("status", status.name());
        result.put("refundedAmount", refundedAmount);
        result.put("resolvedAt", resolvedAt.toString());
        result.putNull("nextActionAt");
        if (failureCode == null) {
            result.putNull("failureCode");
        } else {
            result.put("failureCode", failureCode);
        }
        return result;
    }

    /** Kafka 키(aggregateId)는 orderRef다. */
    public static EventEnvelope<JsonNode> envelope(JsonNode result, Instant occurredAt) {
        return EventEnvelope.create(
                EVENT_TYPE,
                1,
                "payment-service",
                result.get("orderRef").asText(),
                occurredAt,
                result);
    }
}
