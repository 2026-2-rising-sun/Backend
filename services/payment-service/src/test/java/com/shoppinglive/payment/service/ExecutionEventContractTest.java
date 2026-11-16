package com.shoppinglive.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.dto.PaymentExecution;
import com.shoppinglive.payment.dto.RefundExecution;
import com.shoppinglive.payment.entity.PaymentScenario;
import com.shoppinglive.payment.entity.PaymentStatus;
import com.shoppinglive.payment.entity.RefundStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExecutionEventContractTest {
    @Test
    @DisplayName("실제 Payment 발행자의 결제·환불 JSON을 계약 검사 표본으로 저장한다")
    void serializesActualPaymentAndRefundPublishers() throws Exception {
        EventOutbox outbox = mock(EventOutbox.class);
        ExecutionResultPublisher publisher = new ExecutionResultPublisher(outbox);
        Instant requested = Instant.parse("2026-11-09T00:00:00Z");
        Instant resolved = requested.plusSeconds(1);
        String paymentRequest = "a1111111-1111-4111-8111-111111111111";
        String refundRequest = "c1111111-1111-4111-8111-111111111111";
        publisher.payment(
                new PaymentExecution(
                        paymentRequest,
                        7L,
                        1L,
                        1L,
                        "PG-1",
                        1000,
                        PaymentScenario.INSTANT_SUCCESS,
                        PaymentStatus.SUCCESS,
                        requested,
                        resolved,
                        requested));
        publisher.refund(
                new RefundExecution(
                        refundRequest,
                        1L,
                        1L,
                        7L,
                        1L,
                        1L,
                        "PG-1",
                        1000,
                        RefundStatus.SUCCESS,
                        requested,
                        resolved,
                        0,
                        requested));
        capture(
                outbox,
                "payment.execution-results.v1",
                "PaymentExecutionResolved",
                "payment-execution-results.yaml",
                paymentRequest);
        capture(
                outbox,
                "payment.refund-results.v1",
                "RefundExecutionResolved",
                "payment-refund-results.yaml",
                refundRequest);
    }

    private void capture(
            EventOutbox outbox, String topic, String type, String contract, String request)
            throws Exception {
        ArgumentCaptor<EventEnvelope<?>> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(eq(topic), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(type);
        assertThat(event.getValue().aggregateId()).isEqualTo(request);
        assertThat(event.getValue().source()).isEqualTo("payment-service");
        ObjectMapper mapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Path directory = Path.of("build/contract-samples");
        Files.createDirectories(directory);
        mapper.writeValue(
                directory.resolve(type + "-actual.json").toFile(),
                Map.of("contract", contract, "message", type, "payload", event.getValue()));
    }
}
