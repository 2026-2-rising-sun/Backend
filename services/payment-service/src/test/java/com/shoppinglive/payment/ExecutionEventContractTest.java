package com.shoppinglive.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기존 발행 코드는 지웠지만 CommerceCore가 아직 소비하는 결제 결과 계약의 직렬화 표본을 남긴다. 이벤트 표본 검사가 0건이 되지 않게 한다. shortcut: 실제
 * 발행자가 아닌 손으로 만든 payload다. #338 승인 처리에서 PaymentApprovedContractTest로 바꾼다.
 */
class ExecutionEventContractTest {
    @Test
    @DisplayName("결제 결과 이벤트 봉투를 계약 검사 표본으로 저장한다")
    void writesPaymentExecutionResolvedSample() throws Exception {
        Instant requested = Instant.parse("2026-11-09T00:00:00Z");
        Instant resolved = requested.plusSeconds(1);
        String request = "a1111111-1111-4111-8111-111111111111";
        var event =
                EventEnvelope.create(
                        "PaymentExecutionResolved",
                        1,
                        "payment-service",
                        request,
                        resolved,
                        Map.ofEntries(
                                Map.entry("requestId", request),
                                Map.entry("paymentId", 7L),
                                Map.entry("orderId", 1L),
                                Map.entry("paymentGroupId", 1L),
                                Map.entry("referenceNumber", "PG-1"),
                                Map.entry("amount", 1000L),
                                Map.entry("scenario", "INSTANT_SUCCESS"),
                                Map.entry("status", "SUCCESS"),
                                Map.entry("requestedAt", requested),
                                Map.entry("resolvedAt", resolved),
                                Map.entry("scheduledResolveAt", requested)));
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        mapper.writeValue(
                directory.resolve("PaymentExecutionResolved-actual.json").toFile(),
                Map.of(
                        "contract",
                        "payment-execution-results.yaml",
                        "message",
                        "PaymentExecutionResolved",
                        "payload",
                        event));
    }
}
