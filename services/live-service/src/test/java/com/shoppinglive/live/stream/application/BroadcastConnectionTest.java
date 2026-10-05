package com.shoppinglive.live.stream.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("SSE 연결의 순서·대기량 제한·격리")
class BroadcastConnectionTest {
    private static final long BROADCAST = 1L;

    private final BroadcastStreamRegistry registry = new BroadcastStreamRegistry(
        new StreamProperties(Duration.ofSeconds(15), Duration.ofMinutes(30), 2, 2));

    @AfterEach
    void stop() {
        registry.shutdown();
    }

    /** 실제 네트워크 대신 전송 내용을 기록한다. gate 가 있으면 열릴 때까지 전송이 막힌다. */
    private static final class RecordingEmitter extends SseEmitter {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final CountDownLatch firstSendStarted = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final CountDownLatch gate;

        RecordingEmitter(final CountDownLatch gate) {
            this.gate = gate;
        }

        @Override
        public void send(final SseEventBuilder builder) {
            writeLock.lock();
            try {
                firstSendStarted.countDown();
                if (gate != null) {
                    try {
                        gate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                final StringBuilder text = new StringBuilder();
                builder.build().forEach(part -> text.append(part.getData()));
                sent.add(text.toString());
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void complete() {
            super.complete();
            completed.countDown();
        }
    }

    private static StreamEvent chat(final int number) {
        return new StreamEvent("chat.created", String.valueOf(number), number);
    }

    @DisplayName("느린 연결은 대기량을 넘으면 닫히고 등록이 지워지며 다른 연결은 순서대로 모두 받는다")
    @Test
    void slowConnectionIsClosedWithoutBlockingOthers() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        final RecordingEmitter slow = new RecordingEmitter(gate);
        final RecordingEmitter fast = new RecordingEmitter(null);
        registry.register(BROADCAST, slow);
        registry.register(BROADCAST, fast);

        registry.publish(BROADCAST, chat(1));
        assertThat(slow.firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // slow 는 1번 전송에 막혀 있다. 2·3번이 대기 목록(용량 2)을 채우고 4번에서 넘친다.
        awaitSent(fast, 1);
        for (int number = 2; number <= 5; number++) {
            registry.publish(BROADCAST, chat(number));
            awaitSent(fast, number);
        }

        assertThat(slow.completed.getCount()).isEqualTo(1);
        assertThat(registry.connectionCount(BROADCAST)).isEqualTo(1);
        assertThat(fast.sent).extracting(text -> text.substring(text.lastIndexOf("data:")).strip())
            .containsExactly("data:1", "data:2", "data:3", "data:4", "data:5");
        gate.countDown();
        assertThat(slow.completed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @DisplayName("다른 방송의 이벤트는 전달하지 않는다")
    @Test
    void eventsStayInsideTheirBroadcast() throws Exception {
        final RecordingEmitter mine = new RecordingEmitter(null);
        final RecordingEmitter other = new RecordingEmitter(null);
        registry.register(BROADCAST, mine);
        registry.register(2L, other);

        registry.publish(BROADCAST, chat(1));
        registry.publish(2L, chat(2));
        awaitSent(mine, 1);
        awaitSent(other, 1);

        assertThat(mine.sent).singleElement().asString().contains("data:1");
        assertThat(other.sent).singleElement().asString().contains("data:2");
    }

    @DisplayName("마지막 이벤트를 보낸 뒤 연결을 닫고 등록을 지운다")
    @Test
    void publishAndCloseDeliversTheLastEventThenCloses() throws Exception {
        final RecordingEmitter emitter = new RecordingEmitter(null);
        registry.register(BROADCAST, emitter);

        registry.publishAndClose(BROADCAST, StreamEvent.of("broadcast.ended", "bye"));

        assertThat(emitter.completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.sent).singleElement().asString().contains("event:broadcast.ended");
        assertThat(registry.connectionCount(BROADCAST)).isZero();
    }

    @Test
    void rejectedDrainCompletesEvenWhenAnotherCloseAlreadyWon() {
        final RecordingEmitter emitter = new RecordingEmitter(null);
        final AtomicInteger removed = new AtomicInteger();
        final BroadcastConnection[] holder = new BroadcastConnection[1];
        holder[0] = new BroadcastConnection(emitter, 2, task -> {
            holder[0].close();
            throw new RejectedExecutionException("writer stopped");
        }, removed::incrementAndGet);

        holder[0].offer(chat(1));
        holder[0].close();

        assertThat(emitter.completed.getCount()).isZero();
        assertThat(removed.get()).isEqualTo(1);
    }

    @Test
    void shutdownCompletesConnectionsWhoseDrainIsStillQueued() throws Exception {
        final BroadcastStreamRegistry singleWriter = new BroadcastStreamRegistry(
            new StreamProperties(Duration.ofSeconds(15), Duration.ofMinutes(30), 2, 1));
        final CountDownLatch gate = new CountDownLatch(1);
        final RecordingEmitter slow = new RecordingEmitter(gate);
        final RecordingEmitter queued = new RecordingEmitter(null);
        try {
            singleWriter.register(BROADCAST, slow);
            singleWriter.publish(BROADCAST, chat(1));
            assertThat(slow.firstSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            singleWriter.register(2L, queued);
            singleWriter.publish(2L, chat(1));

            singleWriter.shutdown();
            gate.countDown();

            assertThat(slow.completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queued.completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(queued.sent).isEmpty();
        } finally {
            gate.countDown();
            singleWriter.shutdown();
        }
    }

    private static void awaitSent(final RecordingEmitter emitter, final int count) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (emitter.sent.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(emitter.sent).hasSize(count);
    }
}
