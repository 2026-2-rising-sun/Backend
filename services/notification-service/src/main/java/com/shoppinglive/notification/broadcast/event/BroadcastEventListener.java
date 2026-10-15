package com.shoppinglive.notification.broadcast.event;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.broadcast.service.BroadcastStartNotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code live.broadcast.v1} 을 받아 방송 일정 사본을 갱신하고, 방송이 시작되면 신청자에게 알림을 만든다.
 *
 * <p>모르는 {@code eventType} 은 오류가 아니다. 방송 단위 이벤트는 모두 이 토픽에 들어오므로, 알림이 쓰지 않는 종류는 조용히 넘긴다.
 *
 * <p>실패를 직접 다루지 않는다. 해석과 검증 실패는 {@code InvalidEventMessageException} 으로 올라가 <b>보관함 토픽</b>으로 가고, DB
 * 장애 같은 일시 실패는 복구될 때까지 재시도된다. 분류는 {@code NotificationConsumerConfiguration} 에 한 곳으로 모아 뒀다.
 */
@Component
public class BroadcastEventListener {

    private static final int TITLE_MAX_LENGTH = 100;

    private final BroadcastScheduleRepository schedules;
    private final BroadcastStartNotificationService startNotifications;

    public BroadcastEventListener(
            BroadcastScheduleRepository schedules,
            BroadcastStartNotificationService startNotifications) {
        this.schedules = schedules;
        this.startNotifications = startNotifications;
    }

    @KafkaListener(topics = LiveBroadcastEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        LiveBroadcastEventMessage message = LiveBroadcastEventMessage.parse(record.value());
        if (LiveBroadcastEventContract.SCHEDULED.equals(message.eventType())) {
            schedules.apply(toSchedule(message, record.key()));
        } else if (LiveBroadcastEventContract.STARTED.equals(message.eventType())) {
            createStartNotifications(message, record.key());
        }
    }

    private static BroadcastSchedule toSchedule(
            LiveBroadcastEventMessage message, String messageKey) {
        long broadcastId = message.payloadLongAtLeast("broadcastId", 1);
        message.requireKeyMatches(messageKey, broadcastId);
        return new BroadcastSchedule(
                broadcastId,
                message.payloadLongAtLeast("broadcastVersion", 0),
                message.payloadText("title", TITLE_MAX_LENGTH),
                message.payloadInstant("scheduledAt"));
    }

    /**
     * 신청자가 0명이면 아무것도 만들지 않는다. 방송이 시작될 때마다 오는 이벤트라서, 아무도 신청하지 않은 방송에도 온다.
     *
     * <p>알림 시각은 받은 시각이 아니라 payload 의 {@code startedAt} 을 쓴다. 이벤트가 늦게 도착해도 사용자에게는 실제로 일어난 순서로 보여야
     * 한다.
     */
    private void createStartNotifications(LiveBroadcastEventMessage message, String messageKey) {
        long broadcastId = message.payloadLongAtLeast("broadcastId", 1);
        message.requireKeyMatches(messageKey, broadcastId);
        startNotifications.create(
                message.eventId(),
                message.eventType(),
                broadcastId,
                message.payloadText("title", TITLE_MAX_LENGTH),
                message.payloadInstant("startedAt"));
    }
}
