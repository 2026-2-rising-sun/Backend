package com.shoppinglive.commerce.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.contracts.events.DomainEvent;
import com.shoppinglive.contracts.events.SalesInfoEvent;
import com.shoppinglive.contracts.events.SalesInfoEventType;
import com.shoppinglive.contracts.events.SalesInfoSnapshot;
import com.shoppinglive.contracts.events.SalesInfoStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(OutputCaptureExtension.class)
class SalesInfoEventProducerTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DomainEvent> kafka = mock(KafkaTemplate.class);
    private final SalesInfoEvent event = new SalesInfoEvent(UUID.randomUUID().toString(),
        SalesInfoEventType.InventoryChanged, 1, Instant.now(),
        new SalesInfoSnapshot(42, 7, 3, 1000, SalesInfoStatus.ON_SALE, 2, "KRW"));

    @Test
    void publishesUsingContractTopicAndSalesIdKey() {
        when(kafka.send(eq("commerce.sales-info.v1"), eq("42"), any(DomainEvent.class)))
            .thenReturn(new CompletableFuture<>());
        new SalesInfoEventProducer(kafka, true).send(event);
        verify(kafka).send("commerce.sales-info.v1", "42", event);
    }

    @Test
    void synchronousAndAsynchronousFailuresAreLoggedWithoutFailingCommittedRequest(CapturedOutput output) {
        when(kafka.send(eq("commerce.sales-info.v1"), eq("42"), any(DomainEvent.class)))
            .thenThrow(new IllegalStateException("sync failure"))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("async failure")));
        SalesInfoEventProducer producer = new SalesInfoEventProducer(kafka, true);
        assertThatCode(() -> producer.send(event)).doesNotThrowAnyException();
        assertThatCode(() -> producer.send(event)).doesNotThrowAnyException();
        assertThat(output).contains("SALES_EVENT_PUBLISH_FAILED", event.eventId(), "salesId=42", "snapshotVersion=3",
            "sync failure", "async failure");
    }

    @Test
    void disabledTransportNeverConnectsToKafka() {
        new SalesInfoEventProducer(kafka, false).send(event);
        verifyNoInteractions(kafka);
    }
}
