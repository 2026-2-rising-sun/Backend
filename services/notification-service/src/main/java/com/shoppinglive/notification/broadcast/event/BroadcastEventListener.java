package com.shoppinglive.notification.broadcast.event;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.broadcast.service.BroadcastStartNotificationService;
import java.time.Instant;
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
     *
     * <p>알림을 먼저 만들고 시작 표시를 나중에 한다. 표시를 먼저 하면 그 뒤 알림 만들기가 실패했을 때 재시도에서 복구할 근거가 사라진다. 반대로 하면 표시가 실패해도
     * 재시도에서 다시 하고, 이미 만든 알림은 {@code (이벤트ID, 회원ID)} 유일 제약이 걸러 준다.
     */
    private void createStartNotifications(LiveBroadcastEventMessage message, String messageKey) {
        long broadcastId = message.payloadLongAtLeast("broadcastId", 1);
        message.requireKeyMatches(messageKey, broadcastId);
        Instant startedAt = message.payloadInstant("startedAt");
        startNotifications.create(
                message.eventId(),
                message.eventType(),
                broadcastId,
                message.payloadText("title", TITLE_MAX_LENGTH),
                startedAt);
        // 시작한 방송은 임박 알림 대상에서 빠진다. 예정보다 일찍 시작하면 예정 시각이 아직
        // 10분 안이어서, 표시하지 않으면 보고 있는 사람에게 "곧 시작합니다" 가 간다.
        schedules.markStarted(broadcastId, startedAt);
    }
}
