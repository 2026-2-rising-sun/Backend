package com.shoppinglive.live.stream;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 이 pod 에 붙은 SSE 연결만 방송별로 보관한다. 다른 pod 의 연결은 알지 못한다. 전송은 writer 가 맡는다. 연결 하나는 전송 작업을 동시에 최대 1개만
 * 가지므로 writer 수는 연결 수를 넘지 않는다.
 */
@Component
@EnableConfigurationProperties(StreamProperties.class)
public class BroadcastStreamRegistry {
    private static final Logger log = LoggerFactory.getLogger(BroadcastStreamRegistry.class);
    private final Map<Long, Set<BroadcastConnection>> connections = new ConcurrentHashMap<>();
    private final StreamProperties properties;
    // 멈춘 클라이언트는 Tomcat 쓰기 timeout(connection-timeout, 기본 60초)까지 writer 하나를 붙잡는다.
    // 고정 크기 풀이면 멈춘 연결이 writer 수만큼 겹칠 때 이 pod 의 전송이 모두 멈춘다(StalledViewerRedisTest).
    // 그래서 전송마다 가상 스레드를 쓴다. 다만 Tomcat 10.1 의 블로킹 쓰기는 synchronized 안에서 wait 하므로
    // Java 21 에서는 운반 스레드를 붙잡고, JDK 가 운반 스레드를 최대 256개까지 늘려 보충한다(JEP 444).
    // 동시에 멈춘 연결이 그보다 많아지면 다시 밀린다.
    private final ExecutorService writers;

    public BroadcastStreamRegistry(final StreamProperties properties) {
        this.properties = properties;
        this.writers =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("live-stream-writer-", 0).factory());
    }

    /** timeout·오류·완료 어느 경로로 끝나도 등록을 지운다. */
    BroadcastConnection register(final long broadcastId, final SseEmitter emitter) {
        final BroadcastConnection[] holder = new BroadcastConnection[1];
        final BroadcastConnection connection =
                new BroadcastConnection(
                        emitter,
                        properties.queueCapacity(),
                        writers,
                        () -> remove(broadcastId, holder[0]));
        holder[0] = connection;
        // 추가와 remove 의 빈 set 삭제가 같은 key 잠금 안에서 일어나야 삭제된 set 에 연결이 들어가지 않는다.
        connections.compute(
                broadcastId,
                (id, local) -> {
                    final Set<BroadcastConnection> target =
                            local != null ? local : ConcurrentHashMap.newKeySet();
                    target.add(connection);
                    return target;
                });
        emitter.onCompletion(connection::close);
        emitter.onTimeout(connection::close);
        emitter.onError(error -> connection.close());
        return connection;
    }

    /** 이 pod 에 붙은 해당 방송 연결에 전달을 시도한다. 전달은 보장하지 않는다. */
    public void publish(final long broadcastId, final StreamEvent event) {
        local(broadcastId).forEach(connection -> connection.offer(event));
    }

    /** 마지막 이벤트를 보낸 뒤 이 pod 의 해당 방송 연결을 모두 닫는다. */
    public void publishAndClose(final long broadcastId, final StreamEvent event) {
        local(broadcastId).forEach(connection -> connection.offerThenClose(event));
    }

    /** 이 pod 에 연결이 하나 이상 있는 방송. */
    public Set<Long> broadcastIds() {
        return Set.copyOf(connections.keySet());
    }

    public int connectionCount(final long broadcastId) {
        return local(broadcastId).size();
    }

    /** 이 pod 에 열려 있는 SSE 연결 전체. 파드 확장 판단에 쓰는 지표의 근거다. */
    public int connectionCount() {
        int total = 0;
        for (Set<BroadcastConnection> local : connections.values()) {
            total += local.size();
        }
        return total;
    }

    @Scheduled(fixedDelayString = "${live.stream.heartbeat:15s}")
    void heartbeat() {
        connections
                .values()
                .forEach(
                        local ->
                                local.forEach(
                                        connection -> connection.offer(StreamEvent.HEARTBEAT)));
    }

    @PreDestroy
    void shutdown() {
        try {
            connections
                    .values()
                    .forEach(
                            local ->
                                    List.copyOf(local)
                                            .forEach(
                                                    connection -> {
                                                        try {
                                                            connection.close();
                                                        } catch (RuntimeException e) {
                                                            log.warn(
                                                                    "stream connection cleanup failed",
                                                                    e);
                                                        }
                                                    }));
        } finally {
            // close 가 대기 중인 drain 에 완료를 맡길 수 있으므로 예약된 작업도 끝까지 실행한다.
            writers.shutdown();
        }
    }

    private Set<BroadcastConnection> local(final long broadcastId) {
        return connections.getOrDefault(broadcastId, Set.of());
    }

    private void remove(final long broadcastId, final BroadcastConnection connection) {
        connections.computeIfPresent(
                broadcastId,
                (id, local) -> {
                    local.remove(connection);
                    return local.isEmpty() ? null : local;
                });
    }
}
