package com.shoppinglive.common.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxRelayTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
    private final OutboxRelay relay =
            new OutboxRelay(repository, kafka, new OutboxProperties(100, Duration.ofSeconds(1)));

    @Test
    void sendsPendingMessagesInOrderWithKeyAndTypeHeaderThenMarksThemPublished() {
        when(repository.findByPublishedAtIsNullOrderByIdAsc(any(Pageable.class)))
                .thenReturn(
                        List.of(
                                message(1L, "e-1", "LiveBroadcastStarted"),
                                message(2L, "e-2", "LiveBroadcastEnded")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(sent());

        assertEquals(2, relay.relay());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> records =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, org.mockito.Mockito.times(2)).send(records.capture());
        ProducerRecord<String, String> first = records.getAllValues().get(0);
        assertEquals("live.broadcast.v1", first.topic());
        assertEquals("42", first.key());
        assertEquals("{\"eventId\":\"e-1\"}", first.value());
        assertArrayEquals(
                "LiveBroadcastStarted".getBytes(StandardCharsets.UTF_8),
                first.headers().lastHeader(OutboxRelay.EVENT_TYPE_HEADER).value());
        assertEquals(
                "LiveBroadcastEnded",
                new String(
                        records.getAllValues()
                                .get(1)
                                .headers()
                                .lastHeader(OutboxRelay.EVENT_TYPE_HEADER)
                                .value(),
                        StandardCharsets.UTF_8));
        verify(repository).markPublished(eq(1L), any(Instant.class));
        verify(repository).markPublished(eq(2L), any(Instant.class));
    }

    @Test
    void afterCommitListenerDrainsSynchronously() {
        when(repository.findByPublishedAtIsNullOrderByIdAsc(any(Pageable.class)))
                .thenReturn(List.of(message(1L, "e-1", "LiveBroadcastStarted")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(sent());

        relay.onOutboxCommitted(OutboxCommittedEvent.INSTANCE);

        verify(kafka).send(any(ProducerRecord.class));
        verify(repository).markPublished(eq(1L), any(Instant.class));
    }

    @Test
    void reentrantCallDuringSendDoesNotDoubleSend() {
        // 중복 전송을 피하기 위해, fast-path drain 중 다시 relay() 가 호출되면 즉시 0 을 돌려줘야 한다.
        when(repository.findByPublishedAtIsNullOrderByIdAsc(any(Pageable.class)))
                .thenReturn(List.of(message(1L, "e-1", "LiveBroadcastStarted")));
        when(kafka.send(any(ProducerRecord.class)))
                .thenAnswer(
                        invocation -> {
                            // kafka.send 가 호출되는 그 순간 relay 가 또 돌면 안 된다.
                            assertEquals(0, relay.relay());
                            return sent();
                        });

        assertEquals(1, relay.relay());
        verify(kafka, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void stopsAtTheFirstFailureSoLaterEventsDoNotOvertakeIt() {
        when(repository.findByPublishedAtIsNullOrderByIdAsc(any(Pageable.class)))
                .thenReturn(
                        List.of(
                                message(1L, "e-1", "LiveBroadcastStarted"),
                                message(2L, "e-2", "LiveBroadcastEnded")));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertEquals(0, relay.relay());

        verify(kafka, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class));
        verify(repository).markFailed(eq(1L), anyString());
        verify(repository, never()).markPublished(anyLong(), any(Instant.class));
    }

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture(null);
    }

    private static OutboxMessage message(long id, String eventId, String eventType) {
        OutboxMessage message =
                new OutboxMessage(
                        eventId,
                        eventType,
                        "live.broadcast.v1",
                        "42",
                        "{\"eventId\":\"" + eventId + "\"}",
                        Instant.parse("2026-10-25T12:00:00Z"));
        try {
            Field field = OutboxMessage.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(message, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return message;
    }
}
