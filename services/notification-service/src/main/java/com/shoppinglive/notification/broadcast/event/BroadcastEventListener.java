package com.shoppinglive.notification.broadcast.event;

import com.shoppinglive.notification.broadcast.entity.BroadcastSchedule;
import com.shoppinglive.notification.broadcast.repository.BroadcastScheduleRepository;
import com.shoppinglive.notification.broadcast.service.BroadcastStartNotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code live.broadcast.v1} 을 받아 방송 일정 사본을 갱신한다.
 *
 * <p>실패를 두 가지로 나눈다.
 *
 * <ul>
 *   <li><b>해석·검증 실패</b> — 다시 읽어도 같은 결과다. 건너뛴다. 재시도하면 그 메시지에 막혀 뒤의 모든 알림이 멈춘다.
 *   <li><b>그 밖의 실패</b>(DB 장애 등) — 던져서 재시도한다. 잠시 뒤면 성공할 수 있는 일이라 버리면 알림이 유실된다.
 * </ul>
 *
 * <p>모르는 {@code eventType} 은 오류가 아니다. 방송 단위 이벤트는 모두 이 토픽에 들어오므로, 알림이 쓰지 않는 종류는 조용히 넘긴다.
 */
@Component
public class BroadcastEventListener {

    static final String SKIP_MARKER = "BROADCAST_EVENT_SKIPPED";
    private static final int TITLE_MAX_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(BroadcastEventListener.class);

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
        LiveBroadcastEventMessage message;
        try {
            message = LiveBroadcastEventMessage.parse(record.value());
        } catch (LiveBroadcastEventMessage.InvalidMessageException e) {
            skip(record, e);
            return;
        }
        try {
            if (LiveBroadcastEventContract.SCHEDULED.equals(message.eventType())) {
                schedules.apply(toSchedule(message, record.key()));
            } else if (LiveBroadcastEventContract.STARTED.equals(message.eventType())) {
                createStartNotifications(message, record.key());
            }
        } catch (LiveBroadcastEventMessage.InvalidMessageException e) {
            skip(record, e);
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

    private static void skip(
            ConsumerRecord<String, String> record,
            LiveBroadcastEventMessage.InvalidMessageException cause) {
        log.error(
                "{} topic={} partition={} offset={} reason={} raw={}",
                SKIP_MARKER,
                record.topic(),
                record.partition(),
                record.offset(),
                cause.getMessage(),
                record.value());
    }
}
