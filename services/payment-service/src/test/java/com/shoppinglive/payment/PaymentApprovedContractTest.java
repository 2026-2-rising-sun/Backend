package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.payment.payment.event.PaymentApproved;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 승인 반영이 outbox에 쓰는 것과 같은 팩터리로 봉투를 만들어 계약 검사 표본으로 남긴다(event-samples 검사가 읽는다). */
class PaymentApprovedContractTest {
    @Test
    @DisplayName("[PAY-EVENT-01][PAY-EVENT-02] PaymentApproved 봉투를 orderRef 키로 만들고 계약 검사 표본으로 저장한다")
    void writesPaymentApprovedSample() throws Exception {
        Instant approvedAt = Instant.parse("2026-11-10T05:02:11Z");
        PaymentApproved payload =
                new PaymentApproved(1001L, "OD-20261110-K7Q2M9XA", 30000L, approvedAt);
        EventEnvelope<PaymentApproved> event = payload.envelope(approvedAt.plusSeconds(1));

        assertThat(PaymentApproved.TOPIC).isEqualTo("payment.execution.results.v1");
        assertThat(event.eventType()).isEqualTo("PaymentApproved");
        assertThat(event.aggregateId()).isEqualTo("OD-20261110-K7Q2M9XA");

        // Boot 기본 ObjectMapper와 같게 날짜를 ISO 문자열로 쓴다
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        mapper.writeValue(
                directory.resolve("PaymentApproved-actual.json").toFile(),
                Map.of(
                        "contract",
                        "payment-execution-results.yaml",
                        "message",
                        "PaymentApproved",
                        "payload",
                        event));
    }
}
