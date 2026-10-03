package com.shoppinglive.live.stream.application;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 이 pod 에 붙은 SSE 연결만 방송별로 보관한다. 다른 pod 의 연결은 알지 못한다.
 * 전송은 공용 writer 가 맡는다. 연결 하나는 대기 중인 전송 작업을 최대 1개만 가지므로
 * 작업 큐의 크기는 연결 수를 넘지 않는다.
 */
@Component
@EnableConfigurationProperties(StreamProperties.class)
public class BroadcastStreamRegistry {
    private final Map<Long, Set<BroadcastConnection>> connections = new ConcurrentHashMap<>();
    private final StreamProperties properties;
    // ponytail: 멈춘 클라이언트는 컨테이너 write timeout(Tomcat connection-timeout)까지 writer 하나를 붙잡는다.
    // 멈춘 연결이 writer 수만큼 겹치면 그동안 이 pod 의 전송이 모두 밀리고, 밀린 연결은 대기량 초과로 닫힐 수 있다.
    // 부하·장애 테스트에서 측정한 뒤 writer 수 조정 또는 non-blocking 전송으로 바꾼다.
    private final ExecutorService writers;

    public BroadcastStreamRegistry(final StreamProperties properties) {
        this.properties = properties;
        this.writers = Executors.newFixedThreadPool(properties.writerThreads(),
            Thread.ofPlatform().name("live-stream-writer-", 0).daemon().factory());
    }

    /** timeout·오류·완료 어느 경로로 끝나도 등록을 지운다. */
    BroadcastConnection register(final long broadcastId, final SseEmitter emitter) {
        final BroadcastConnection[] holder = new BroadcastConnection[1];
        final BroadcastConnection connection = new BroadcastConnection(emitter, properties.queueCapacity(),
            writers, () -> remove(broadcastId, holder[0]));
        holder[0] = connection;
        // 추가와 remove 의 빈 set 삭제가 같은 key 잠금 안에서 일어나야 삭제된 set 에 연결이 들어가지 않는다.
        connections.compute(broadcastId, (id, local) -> {
            final Set<BroadcastConnection> target = local != null ? local : ConcurrentHashMap.newKeySet();
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

    public int connectionCount(final long broadcastId) {
        return local(broadcastId).size();
    }

    @Scheduled(fixedDelayString = "${live.stream.heartbeat:15s}")
    void heartbeat() {
        connections.values().forEach(local -> local.forEach(connection -> connection.offer(StreamEvent.HEARTBEAT)));
    }

    @PreDestroy
    void shutdown() {
        connections.values().forEach(local -> List.copyOf(local).forEach(BroadcastConnection::close));
        writers.shutdownNow();
    }

    private Set<BroadcastConnection> local(final long broadcastId) {
        return connections.getOrDefault(broadcastId, Set.of());
    }

    private void remove(final long broadcastId, final BroadcastConnection connection) {
        connections.computeIfPresent(broadcastId, (id, local) -> {
            local.remove(connection);
            return local.isEmpty() ? null : local;
        });
    }
}
