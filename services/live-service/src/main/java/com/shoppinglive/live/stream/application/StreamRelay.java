package com.shoppinglive.live.stream.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시청자는 여러 pod 에 나뉘어 붙는다. 이벤트를 Redis Pub/Sub 으로 발행하면 모든 pod 가 받아
 * 자기에게 붙은 연결에 전달한다. 발행한 pod 도 Redis 로 받은 것만 전달하므로 중복이 없다.
 * Redis 장애 중에는 실시간 전달이 멈춘다. 저장된 채팅은 최근 내역 조회로 복구한다.
 */
@Component
public class StreamRelay implements MessageListener {
    static final String CHANNEL = "live:events";
    /** 이 이벤트를 받은 pod 는 전달한 뒤 해당 방송의 연결을 닫는다. */
    public static final String BROADCAST_ENDED = "broadcast.ended";
    private static final Logger log = LoggerFactory.getLogger(StreamRelay.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final BroadcastStreamRegistry registry;
    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    private volatile boolean subscribed;

    record Envelope(String type, long broadcastId, String id, JsonNode data) {}

    public StreamRelay(final StringRedisTemplate redis, final ObjectMapper mapper,
                       final BroadcastStreamRegistry registry) {
        this.redis = redis;
        this.mapper = mapper;
        this.registry = registry;
        container.setConnectionFactory(redis.getRequiredConnectionFactory());
        container.addMessageListener(this, new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
    }

    /** 발행 실패는 호출자에게 알리지 않는다. 이미 커밋된 요청의 성공을 바꾸지 않기 위해서다. */
    public void publish(final long broadcastId, final StreamEvent event) {
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(
                new Envelope(event.name(), broadcastId, event.id(), mapper.valueToTree(event.data()))));
        } catch (Exception e) {
            log.warn("stream event not published: type={} broadcastId={} cause={}", event.name(), broadcastId,
                e.toString());
        }
    }

    @Override
    public void onMessage(final Message message, final byte[] pattern) {
        try {
            final Envelope envelope = mapper.readValue(message.getBody(), Envelope.class);
            final StreamEvent event = new StreamEvent(envelope.type(), envelope.id(), envelope.data());
            if (BROADCAST_ENDED.equals(envelope.type())) {
                registry.publishAndClose(envelope.broadcastId(), event);
            } else {
                registry.publish(envelope.broadcastId(), event);
            }
        } catch (Exception e) {
            log.warn("stream event ignored: cause={}", e.toString());
        }
    }

    /**
     * 구독을 앱 기동과 분리한다. 기동 시 Redis 가 없어도 앱은 뜨고, Redis 가 돌아오면 여기서 구독한다.
     * 구독 뒤의 연결 끊김은 container 가 스스로 복구한다.
     */
    @Scheduled(fixedDelayString = "${live.stream.subscribe-retry:5s}")
    synchronized void subscribe() {
        if (subscribed) {
            return;
        }
        try {
            container.start();
            subscribed = true;
            log.info("stream relay subscribed");
        } catch (RuntimeException e) {
            // 실패한 start 가 container 를 "실행 중"으로 남기면 다음 start 가 아무것도 하지 않는다.
            container.stop();
            log.warn("stream relay not subscribed, will retry: cause={}", e.toString());
        }
    }

    boolean subscribed() {
        return subscribed;
    }

    @PreDestroy
    void stop() throws Exception {
        container.destroy();
    }
}
