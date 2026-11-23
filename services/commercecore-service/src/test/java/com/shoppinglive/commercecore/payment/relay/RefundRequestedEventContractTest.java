package com.shoppinglive.commercecore.payment.relay;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shoppinglive.common.events.EventEnvelope;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;

class RefundRequestedEventContractTest {
    @Test
    @DisplayName("이행 전 저장된 LATE_APPROVAL 요청 필드를 보존한다")
    void serializesLateApprovalRequestContract() throws Exception {
        var mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String ref = "PG-22222222-2222-4222-8222-222222222222";
        var event =
                EventEnvelope.create(
                        "RefundRequested",
                        1,
                        "commercecore-service",
                        ref,
                        Instant.parse("2026-11-16T01:20:00Z"),
                        new PaymentRelayContract.RefundRequested(
                                "late-7001", 7001, ref, "LATE_APPROVAL"));
        var json = mapper.valueToTree(event);
        assertThat(json.path("aggregateId").asText()).isEqualTo(ref);
        assertThat(json.path("payload").size()).isEqualTo(4);
        assertThat(json.path("payload").path("refundRequestId").asText()).isEqualTo("late-7001");
        var path = Path.of("build/contract-samples/RefundRequested.json");
        Files.createDirectories(path.getParent());
        mapper.writeValue(
                path.toFile(),
                Map.of(
                        "contract",
                        "commerce-refund-requests.yaml",
                        "message",
                        "RefundRequested",
                        "payload",
                        event));
    }
}
