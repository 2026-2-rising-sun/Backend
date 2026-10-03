package com.shoppinglive.live.stream.application;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 시청자 한 명의 SSE 연결과 아직 못 보낸 이벤트 목록. 한 번에 한 스레드만 전송해 순서를 지킨다.
 * 목록이 가득 차면 느린 연결로 보고 이 연결만 닫는다. 클라이언트는 재연결 후 최근 채팅으로 복구한다.
 */
final class BroadcastConnection {
    private final SseEmitter emitter;
    private final Queue<StreamEvent> pending;
    private final Executor writers;
    private final Runnable onClose;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    BroadcastConnection(final SseEmitter emitter, final int capacity, final Executor writers,
                        final Runnable onClose) {
        this.emitter = emitter;
        this.pending = new ArrayBlockingQueue<>(capacity);
        this.writers = writers;
        this.onClose = onClose;
    }

    void offer(final StreamEvent event) {
        if (closed.get()) {
            return;
        }
        if (!pending.offer(event)) {
            close();
            return;
        }
        scheduleDrain();
    }

    /** 대기 중인 이벤트를 보낸 뒤 닫는다. 종료 이벤트처럼 마지막으로 전달할 것이 있을 때 쓴다. */
    void offerThenClose(final StreamEvent event) {
        offer(event);
        offer(CLOSE);
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.clear();
        onClose.run();
        emitter.complete();
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            writers.execute(this::drain);
        } catch (RejectedExecutionException e) {
            close();
        }
    }

    private void drain() {
        try {
            StreamEvent event = pending.poll();
            while (event != null && !closed.get()) {
                if (event == CLOSE) {
                    close();
                    return;
                }
                send(event);
                event = pending.poll();
            }
        } catch (Exception e) {
            // 클라이언트가 끊었거나 이미 완료된 emitter 다. 재시도하지 않는다.
            close();
        } finally {
            draining.set(false);
            if (!pending.isEmpty() && !closed.get()) {
                scheduleDrain();
            }
        }
    }

    private void send(final StreamEvent event) throws Exception {
        if (event.name() == null) {
            emitter.send(SseEmitter.event().comment("heartbeat"));
            return;
        }
        final SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event.name());
        if (event.id() != null) {
            builder.id(event.id());
        }
        emitter.send(builder.data(event.data(), MediaType.APPLICATION_JSON));
    }

    private static final StreamEvent CLOSE = new StreamEvent("close", null, null);
}
