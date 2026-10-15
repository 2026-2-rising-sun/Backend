package com.shoppinglive.commerce.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.common.events.EventEnvelope;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(OutputCaptureExtension.class)
class SalesInfoEventProducerTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, EventEnvelope<SalesInfoSnapshot>> kafka =
            mock(KafkaTemplate.class);

    private final EventEnvelope<SalesInfoSnapshot> event =
            SalesInfoEventContract.create(
                    SalesInfoEventType.InventoryChanged,
                    Instant.now(),
                    new SalesInfoSnapshot(42, 7, 3, 1000, SalesInfoStatus.ON_SALE, 2, "KRW"));

    @DisplayName("계약 토픽과 판매 ID 키로 이벤트를 발행한다")
    @Test
    void publishesUsingContractTopicAndSalesIdKey() {
        when(kafka.send(eq("commerce.sales-info.v1"), eq("42"), any()))
                .thenReturn(new CompletableFuture<>());
        new SalesInfoEventProducer(kafka, true).send(event);
        verify(kafka).send("commerce.sales-info.v1", "42", event);
    }

    @DisplayName("동기·비동기 전송 실패는 로그에 남기고 커밋된 요청을 실패시키지 않는다")
    @Test
    void synchronousAndAsynchronousFailuresAreLoggedWithoutFailingCommittedRequest(
            CapturedOutput output) {
        when(kafka.send(eq("commerce.sales-info.v1"), eq("42"), any()))
                .thenThrow(new IllegalStateException("sync failure"))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("async failure")));
        SalesInfoEventProducer producer = new SalesInfoEventProducer(kafka, true);
        assertThatCode(() -> producer.send(event)).doesNotThrowAnyException();
        assertThatCode(() -> producer.send(event)).doesNotThrowAnyException();
        assertThat(output)
                .contains(
                        "SALES_EVENT_PUBLISH_FAILED",
                        event.eventId(),
                        "salesId=42",
                        "snapshotVersion=3",
                        "sync failure",
                        "async failure");
    }

    @DisplayName("전송이 비활성화되면 Kafka에 연결하지 않는다")
    @Test
    void disabledTransportNeverConnectsToKafka() {
        new SalesInfoEventProducer(kafka, false).send(event);
        verifyNoInteractions(kafka);
    }
}
