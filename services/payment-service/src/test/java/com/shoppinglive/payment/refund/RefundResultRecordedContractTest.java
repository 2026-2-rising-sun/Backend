package com.shoppinglive.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.payment.refund.event.RefundResultRecorded;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 접수가 outbox에 쓰는 것과 같은 팩터리로 결과 봉투를 만들어 계약 검사 표본으로 남긴다(event-samples 검사가 읽는다). */
class RefundResultRecordedContractTest {
    static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName(
            "[REF-EVENT-01] 결과는 요청 원문에 Payment 필드 7개를 더하고 orderRef 키로 payment.refund.results.v1에 간다")
    void writesFailedResultSample() throws Exception {
        JsonNode request =
                MAPPER.readTree(
                        """
                        {"refundRequestId":"b1111111-1111-4111-8111-111111111111",
                         "orderRef":"PG-22222222-2222-4222-8222-222222222222",
                         "purpose":"SYSTEM_COMPENSATION","reason":"APPROVAL_UNKNOWN","currency":"KRW",
                         "amount":4000,"items":[{"sellerOrderNumber":"ORDER-301","amount":4000,
                         "orderItems":[{"orderItemId":301,"quantity":1,"amount":4000}]}],
                         "requestedAt":"2026-11-19T00:59:59.123456789Z"}
                        """);
        JsonNode result =
                RefundResultRecorded.failed(
                        request,
                        UUID.fromString("d1111111-1111-4111-8111-000000000001"),
                        "PAYMENT_ID_REQUIRED",
                        Instant.parse("2026-11-21T01:00:01.123456Z"));
        EventEnvelope<JsonNode> event =
                RefundResultRecorded.envelope(result, Instant.parse("2026-11-21T01:00:01Z"));

        assertThat(RefundResultRecorded.TOPIC).isEqualTo("payment.refund.results.v1");
        assertThat(event.eventType()).isEqualTo("RefundResultRecorded");
        assertThat(event.aggregateId()).isEqualTo("PG-22222222-2222-4222-8222-222222222222");
        assertThat(result.get("requestedAt")).isEqualTo(request.get("requestedAt"));
        assertThat(result.get("resolvedAt").asText()).isEqualTo("2026-11-21T01:00:01.123456Z");

        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        MAPPER.writeValue(
                directory.resolve("RefundResultRecorded-actual.json").toFile(),
                Map.of(
                        "contract",
                        "payment-refund-results.yaml",
                        "message",
                        "RefundResultRecorded",
                        "payload",
                        event));
    }
}
