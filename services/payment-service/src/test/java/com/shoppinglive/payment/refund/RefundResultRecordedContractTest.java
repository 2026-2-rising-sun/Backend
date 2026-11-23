package com.shoppinglive.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.event.RefundRequestParser;
import com.shoppinglive.payment.refund.event.RefundResultRecorded;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /** 파서가 받아들인 요청으로 접수가 만드는 실패 결과를 표본으로 남긴다. check_events --samples가 실제 생산 결과를 결과 계약으로 검사한다. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "user-no-payment-id,USER_REFUND,CUSTOMER_REQUEST,,PAYMENT_ID_REQUIRED",
        "user-not-found,USER_REFUND,CUSTOMER_REQUEST,7001,PAYMENT_NOT_FOUND",
        "user-mismatch,USER_REFUND,CUSTOMER_REQUEST,7002,PAYMENT_MISMATCH",
        "system-in-progress,SYSTEM_COMPENSATION,LATE_APPROVAL,7001,COMPENSATION_IN_PROGRESS",
        "system-no-payment-id,SYSTEM_COMPENSATION,APPROVAL_UNKNOWN,,PAYMENT_ID_REQUIRED"
    })
    @DisplayName("[REF-EVENT-01][REF-ACCEPT-05][REF-ACCEPT-06] 파서가 받은 요청의 실패 결과는 결과 계약을 지킨다")
    void writesFailedResultSamplesFromParsedRequests(
            String name, String purpose, String reason, Long paymentId, String failureCode)
            throws Exception {
        String orderRef = "PG-22222222-2222-4222-8222-222222222222";
        String payment = paymentId == null ? "" : "\"paymentId\":" + paymentId + ",";
        String event =
                """
                {"eventId":"11111111-1111-4111-8111-000000000009","eventType":"RefundRequested",
                 "schemaVersion":1,"source":"commercecore-service","aggregateId":"%s",
                 "occurredAt":"2026-11-21T01:00:00Z","payload":{"refundRequestId":"r-%s",
                 "orderRef":"%s",%s"purpose":"%s","reason":"%s","currency":"KRW","amount":4000,
                 "items":[{"sellerOrderNumber":"ORDER-301","amount":4000,
                 "orderItems":[{"orderItemId":301,"quantity":1,"amount":4000}]}],
                 "requestedAt":"2026-11-21T00:59:59.123456789Z"}}
                """
                        .formatted(orderRef, name, orderRef, payment, purpose, reason);
        JsonNode request = RefundRequestParser.parse(orderRef, event);
        JsonNode result =
                RefundResultRecorded.failed(request, UUID.randomUUID(), failureCode, Instant.now());

        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        MAPPER.writeValue(
                directory.resolve("RefundResultRecorded-" + name + ".json").toFile(),
                Map.of(
                        "contract",
                        "payment-refund-results.yaml",
                        "message",
                        "RefundResultRecorded",
                        "payload",
                        RefundResultRecorded.envelope(result, Instant.now())));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("[REF-EXEC-05] 0원 고객 환불의 SUCCEEDED(refundedAmount 0)도 결과 계약을 지킨다")
    void writesZeroRefundSample() throws Exception {
        JsonNode request =
                MAPPER.readTree(
                        """
                        {"refundRequestId":"x-user-zero","orderRef":"PG-22222222-2222-4222-8222-222222222222",
                         "paymentId":7001,"purpose":"USER_REFUND","reason":"CUSTOMER_REQUEST","currency":"KRW",
                         "amount":0,"items":[{"sellerOrderNumber":"ORDER-301","amount":0,
                         "orderItems":[{"orderItemId":301,"quantity":1,"amount":0}]}],
                         "requestedAt":"2026-11-21T00:59:59.123456789Z"}
                        """);
        JsonNode result =
                RefundResultRecorded.result(
                        request,
                        UUID.randomUUID(),
                        1,
                        RefundStatus.SUCCEEDED,
                        0,
                        Instant.now(),
                        null);

        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        MAPPER.writeValue(
                directory.resolve("RefundResultRecorded-user-zero-succeeded.json").toFile(),
                Map.of(
                        "contract",
                        "payment-refund-results.yaml",
                        "message",
                        "RefundResultRecorded",
                        "payload",
                        RefundResultRecorded.envelope(result, Instant.now())));
        assertThat(result.get("refundedAmount").longValue()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "user-succeeded,USER_REFUND,CUSTOMER_REQUEST,1,SUCCEEDED,4000,",
        "system-failed,SYSTEM_COMPENSATION,LATE_APPROVAL,1,FAILED,0,REJECT_CARD_COMPANY",
        "system-succeeded-v2,SYSTEM_COMPENSATION,LATE_APPROVAL,2,SUCCEEDED,4000,",
        "system-no-charge-v2,SYSTEM_COMPENSATION,APPROVAL_UNKNOWN,2,NO_CHARGE,0,NO_CHARGE"
    })
    @DisplayName("[REF-EXEC-02][REF-EXEC-03][REF-LATE-02] 실행 결과(성공·실패·미승인, 버전 2 포함)도 결과 계약을 지킨다")
    void writesExecutionResultSamples(
            String name,
            String purpose,
            String reason,
            int version,
            RefundStatus status,
            long refundedAmount,
            String failureCode)
            throws Exception {
        JsonNode request =
                MAPPER.readTree(
                        """
                        {"refundRequestId":"x-%s","orderRef":"PG-22222222-2222-4222-8222-222222222222",
                         "paymentId":7001,"purpose":"%s","reason":"%s","currency":"KRW","amount":4000,
                         "items":[{"sellerOrderNumber":"ORDER-301","amount":4000,
                         "orderItems":[{"orderItemId":301,"quantity":1,"amount":4000}]}],
                         "requestedAt":"2026-11-21T00:59:59.123456789Z"}
                        """
                                .formatted(name, purpose, reason));
        JsonNode result =
                RefundResultRecorded.result(
                        request,
                        UUID.randomUUID(),
                        version,
                        status,
                        refundedAmount,
                        Instant.now(),
                        failureCode);
        assertThat(result.get("resultVersion").intValue()).isEqualTo(version);
        assertThat(result.get("failureCode").isNull()).isEqualTo(failureCode == null);

        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        MAPPER.writeValue(
                directory.resolve("RefundResultRecorded-" + name + ".json").toFile(),
                Map.of(
                        "contract",
                        "payment-refund-results.yaml",
                        "message",
                        "RefundResultRecorded",
                        "payload",
                        RefundResultRecorded.envelope(result, Instant.now())));
    }
}
