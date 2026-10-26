package com.shoppinglive.live.stream;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 시청자 한 명의 SSE 연결과 아직 못 보낸 이벤트 목록. 한 번에 한 스레드만 전송해 순서를 지킨다. 목록이 가득 차면 느린 연결로 보고 이 연결만 닫는다. 클라이언트는
 * 재연결 후 최근 채팅으로 복구한다.
 */
final class BroadcastConnection {
    private static final Logger log = LoggerFactory.getLogger(BroadcastConnection.class);
    private final SseEmitter emitter;
    private final Queue<StreamEvent> pending;
    private final Executor writers;
    private final Runnable onClose;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean active = new AtomicBoolean();
    private List<StreamEvent> firstEvents = List.of();
    private volatile StreamEvent terminalEvent;

    BroadcastConnection(
            final SseEmitter emitter,
            final int capacity,
            final Executor writers,
            final Runnable onClose) {
        this.emitter = emitter;
        this.pending = new ArrayBlockingQueue<>(capacity);
        this.writers = writers;
        this.onClose = onClose;
    }

    void offer(final StreamEvent event) {
        final boolean accepted;
        synchronized (this) {
            if (closed.get() || terminalEvent != null) {
                return;
            }
            accepted = pending.offer(event);
        }
        if (accepted) {
            scheduleDrain();
        } else {
            close();
        }
    }

    void activate(final StreamEvent first) {
        activate(List.of(first));
    }

    /** 상태 확인 전 이벤트는 큐에만 쌓고, 주어진 이벤트부터 전송을 시작한다. 이 이벤트들은 대기 한도에 세지 않으므로 호출자가 개수를 제한한다. */
    void activate(final List<StreamEvent> first) {
        synchronized (this) {
            if (closed.get() || active.get()) {
                return;
            }
            firstEvents = List.copyOf(first);
            active.set(true);
        }
        scheduleDrain();
    }

    /**
     * 활성화 전에 호출한 스레드에서 바로 보낸다. 컨트롤러가 반환하기 전에 요청 스레드에서 부르면 Spring 이 담아 두었다가 요청 스레드에서 응답을 확정한다. 그러면
     * 보안 헤더 필터가 처리 뒤에 헤더를 쓰는 동안 writer 스레드가 응답을 확정해 Tomcat 헤더를 동시에 고치는 일이 없다(Servlet 응답 객체는 스레드
     * 안전하지 않다). 활성화 전이라 drain 과 겹치지 않는다.
     */
    void sendBeforeActivate(final StreamEvent event) throws Exception {
        if (active.get()) {
            throw new IllegalStateException("이미 활성화된 연결입니다.");
        }
        send(event);
    }

    /** 마지막 이벤트 등록과 추가 전송 차단을 함께 처리한다. 전송이 끝나면 writer가 닫는다. */
    void offerThenClose(final StreamEvent event) {
        final boolean accepted;
        synchronized (this) {
            if (closed.get() || terminalEvent != null) {
                return;
            }
            terminalEvent = event;
            accepted = pending.offer(event);
        }
        if (accepted) {
            scheduleDrain();
        } else {
            close();
        }
    }

    void close() {
        final boolean firstClose;
        synchronized (this) {
            firstClose = closed.compareAndSet(false, true);
            if (firstClose) {
                pending.clear();
            }
        }
        if (firstClose) {
            onClose.run();
        }
        // complete 는 send 와 같은 잠금을 사용한다. 전송 중이면 writer 가 완료하도록 맡긴다.
        if (!draining.get()) {
            complete();
        }
    }

    private void scheduleDrain() {
        if (closed.get() || !active.get()) {
            return;
        }
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            writers.execute(this::drain);
        } catch (RejectedExecutionException e) {
            draining.set(false);
            close();
        }
    }

    private void drain() {
        try {
            final List<StreamEvent> head = firstEvents;
            firstEvents = List.of();
            for (final StreamEvent first : head) {
                if (closed.get()) {
                    break;
                }
                send(first);
            }
            StreamEvent event = pending.poll();
            while (event != null && !closed.get()) {
                send(event);
                if (event == terminalEvent) {
                    close();
                    return;
                }
                event = pending.poll();
            }
        } catch (Exception e) {
            // 클라이언트가 끊었거나 이미 완료된 emitter 다. 재시도하지 않는다.
            close();
        } finally {
            draining.set(false);
            if (closed.get()) {
                complete();
            } else if (!pending.isEmpty()) {
                scheduleDrain();
            }
        }
    }

    private void complete() {
        if (completed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                // 재사용이 끝난 servlet 응답의 완료 실패가 다른 연결 정리를 막지 않게 한다.
                log.debug("스트림 종료 실패", e);
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
}
